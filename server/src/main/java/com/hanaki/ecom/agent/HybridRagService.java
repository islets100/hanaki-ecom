package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hanaki.ecom.context.RetrievedChunkRef;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.rag.RagEvidenceValidationService;
import com.hanaki.ecom.rag.RagPipelineModels.DegradationEvent;
import com.hanaki.ecom.rag.RagPipelineModels.DocumentType;
import com.hanaki.ecom.rag.RagPipelineModels.EvidenceValidationResult;
import com.hanaki.ecom.rag.RagPipelineModels.FusionResult;
import com.hanaki.ecom.rag.RagPipelineModels.QueryRewriteResult;
import com.hanaki.ecom.rag.RagPipelineModels.RankedCandidate;
import com.hanaki.ecom.rag.RagPipelineModels.RecallResult;
import com.hanaki.ecom.rag.RagPipelineModels.RerankResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalBranch;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalSource;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalStrategy;
import com.hanaki.ecom.rag.RagRrfFusionService;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.security.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * RAG 检索链路的执行服务。
 *
 * <p>公开方法与 Business Agent 子图的节点一一对应：recall 只做多路召回，fuse 只做 RRF，
 * rerank 只调用重排并校验返回名次，validate 只做证据准入。节点拆开后，每一步都能独立计时、
 * checkpoint 和降级，不会再出现“混合检索失败但日志只显示 rewrite_retrieve 失败”的黑盒。</p>
 */
@Service
public class HybridRagService {
    private final EcommerceStore store;
    private final VersionedAgentCache cache;
    private final EmbeddingCacheService embeddings;
    private final AgentTelemetryService telemetry;
    private final DashScopeRerankClient reranker;
    private final ElasticsearchKnowledgeIndex elasticsearch;
    private final RagRrfFusionService fusionService;
    private final RagEvidenceValidationService evidenceValidator;
    private final long branchTimeoutMillis;
    private final String rerankModel;

    /**
     * 文档向量只放在本实例的有界 L1。键包含 tenant/doc/version/embeddingModel，知识或模型升级后
     * 自动进入新键。正文绝不写入跨实例查询缓存，避免 Redis 中形成第二份不可及时撤销的知识库。
     */
    private final Cache<String, float[]> documentVectorCache = Caffeine.newBuilder()
            .maximumWeight(32L * 1_024 * 1_024)
            .weigher((String key, float[] vector) -> Math.max(1,
                    key.length() * Character.BYTES + vector.length * Float.BYTES))
            .expireAfterAccess(Duration.ofHours(24))
            .recordStats()
            .build();

    public HybridRagService(EcommerceStore store,
                            VersionedAgentCache cache,
                            EmbeddingCacheService embeddings,
                            AgentTelemetryService telemetry,
                            DashScopeRerankClient reranker,
                            ElasticsearchKnowledgeIndex elasticsearch,
                            RagRrfFusionService fusionService,
                            RagEvidenceValidationService evidenceValidator,
                            @Value("${agent.rag.recall.branch-timeout-millis:4500}") long branchTimeoutMillis,
                            @Value("${agent.rag.rerank.model:qwen3-rerank}") String rerankModel) {
        this.store = store;
        this.cache = cache;
        this.embeddings = embeddings;
        this.telemetry = telemetry;
        this.reranker = reranker;
        this.elasticsearch = elasticsearch;
        this.fusionService = fusionService;
        this.evidenceValidator = evidenceValidator;
        this.branchTimeoutMillis = Math.max(100L, branchTimeoutMillis);
        this.rerankModel = rerankModel == null ? "unknown-rerank" : rerankModel;
    }

    /**
     * 执行 BM25、向量、规则等召回分支。BM25 与向量通过独立虚拟线程同时开始，并分别设置超时；
     * 任一路失败只产生自己的 DegradationEvent，另一条正常结果仍会进入 RRF。只有两个主检索分支
     * 都失败时才返回空候选，由证据节点阻止模型无依据回答。
     */
    public RecallResult recall(String trustedTenantId, RetrievalPlan plan, QueryRewriteResult rewrite) {
        if (plan.skipKnowledge()) return emptyRecall("skipped");
        List<KnowledgeDoc> documents = store.knowledge(trustedTenantId, plan.domain());
        if (documents.isEmpty()) return emptyRecall("empty-source");

        String sourceVersion = sourceVersion(documents);
        Map<String, KnowledgeDoc> currentById = byId(documents);
        String documentScope = plan.documentTypes().stream().map(Enum::name).sorted()
                .reduce((left, right) -> left + "," + right).orElse("ALL");

        /*
         * 缓存键完整覆盖租户（在 CacheContext）、规范化查询、文档范围、索引/政策/embedding/rerank/
         * 配置版本。任何会改变候选集合或排名的版本升级都会自然进入新键；降级结果明确不写缓存。
         */
        String projection = String.join("|",
                "lexical=" + QueryNormalizer.normalize(rewrite.lexicalQuery()),
                "semantic=" + QueryNormalizer.normalize(rewrite.semanticQuery()),
                "scope=" + documentScope,
                "strategy=" + plan.strategy(),
                "branches=" + plan.branches(),
                "indexVersion=" + plan.indexVersion(),
                "policyVersion=" + plan.policyVersion(),
                "embeddingVersion=" + embeddings.modelVersion(),
                "rerankVersion=" + rerankModel,
                "configVersion=" + plan.configVersion(),
                "topK=" + plan.lexicalTopK() + ":" + plan.vectorTopK() + ":" + plan.vectorNumCandidates());
        CacheContext context = CacheContext.tenant(trustedTenantId,
                "knowledge:read|domain=" + plan.domain() + "|types=" + documentScope,
                sourceVersion, embeddings.modelVersion(), "");

        return cache.getOrLoad(CachePolicy.retrieval(), context, projection, sourceVersion,
                        new TypeReference<RecallResult>() {},
                        value -> validRecall(value, trustedTenantId, currentById, plan, sourceVersion),
                        () -> {
                            RecallResult result = recallUncached(trustedTenantId, plan, rewrite, documents);
                            return result.degraded()
                                    ? CacheLoadResult.doNotCache(result)
                                    : CacheLoadResult.success(result, sourceVersion);
                        })
                .orElseGet(() -> emptyRecall(sourceVersion));
    }

    /** RRF 节点。正文只在该方法栈内用于内容摘要去重，不写入返回状态。 */
    public FusionResult fuse(String trustedTenantId, RetrievalPlan plan, RecallResult recall) {
        List<KnowledgeDoc> current = store.knowledge(trustedTenantId, plan.domain());
        return telemetry.observeCurrent("rag.fusion.rrf", "RETRIEVAL",
                Map.of("branchCount", plan.branches().size(), "candidateCount", candidateCount(recall)),
                Map.of("strategy", plan.strategy().name(), "configVersion", plan.configVersion()),
                () -> fusionService.fuse(recall, plan, current));
    }

    /**
     * 把融合后的 Top-N 正文临时交给 Rerank。外部服务只允许返回输入数组中的 index 与有限分数；
     * 超时、非法下标、重复下标或空结果都降级为 RRF 顺序。无论成功还是降级，Graph State 只接收
     * RetrievedChunkRef，不接收传给外部服务的正文。
     */
    public RerankResult rerank(String trustedTenantId, RetrievalPlan plan,
                               QueryRewriteResult rewrite, FusionResult fusion) {
        if (plan.skipKnowledge() || fusion.candidates().isEmpty())
            return new RerankResult(List.of(), fusion.degradations(), reranker.enabled() ? rerankModel : "rrf-only");
        Map<String, KnowledgeDoc> currentById = byId(store.knowledge(trustedTenantId, plan.domain()));
        List<RankedCandidate> input = fusion.candidates().stream()
                .filter(candidate -> currentById.containsKey(candidate.chunkId()))
                .limit(plan.rerankTopN()).toList();
        if (input.isEmpty()) return new RerankResult(List.of(), fusion.degradations(), "empty");

        List<DegradationEvent> degradations = new ArrayList<>(fusion.degradations());
        if (reranker.enabled()) {
            try {
                List<String> documents = input.stream().map(candidate -> {
                    KnowledgeDoc doc = currentById.get(candidate.chunkId());
                    return doc.title() + "\n" + doc.content();
                }).toList();
                var response = reranker.rerank(rewrite.semanticQuery(), documents, plan.finalTopK());
                Set<Integer> seen = new HashSet<>();
                List<RetrievedChunkRef> references = new ArrayList<>();
                for (var item : response.documents()) {
                    if (!seen.add(item.index()))
                        throw new DashScopeRerankClient.RerankUnavailableException(
                                "Rerank response contains duplicate document index");
                    RankedCandidate candidate = input.get(item.index());
                    references.add(new RetrievedChunkRef(candidate.chunkId(), candidate.documentVersion(), item.score()));
                    if (references.size() >= plan.finalTopK()) break;
                }
                if (!references.isEmpty()) return new RerankResult(references, degradations, rerankModel);
            } catch (DashScopeRerankClient.RerankUnavailableException error) {
                degradations.add(new DegradationEvent("RERANK", "REMOTE_RERANK_UNAVAILABLE",
                        safeMessage(error)));
            }
        }

        List<RetrievedChunkRef> fallback = input.stream().limit(plan.finalTopK())
                .map(candidate -> new RetrievedChunkRef(candidate.chunkId(), candidate.documentVersion(),
                        candidate.fusedScore())).toList();
        return new RerankResult(fallback, degradations, reranker.enabled() ? "rrf-fallback" : "rrf-only");
    }

    /** 最终证据准入；物化数据来自当前租户权威源，旧 checkpoint 中的正文不可能被复用。 */
    public EvidenceValidationResult validate(String trustedTenantId, RetrievalPlan plan,
                                             QueryRewriteResult rewrite, RerankResult rerank) {
        return telemetry.observeCurrent("rag.evidence.validate", "RETRIEVAL",
                Map.of("candidateCount", rerank.references().size(), "subQuestionCount", rewrite.subQuestions().size()),
                Map.of("policyVersion", plan.policyVersion()),
                () -> evidenceValidator.validate(trustedTenantId, plan, rewrite, rerank,
                        store.knowledge(trustedTenantId, plan.domain())));
    }

    /**
     * 兼容旧调用方的一站式入口。新的 Business Agent Graph 不使用它，而是逐节点调用上述方法。
     * 该入口仍采用同一套结构化状态、RRF、Rerank 与证据验证，避免出现两套检索语义。
     */
    public List<KnowledgeDoc> search(String tenantId, String domain, String rewrittenQuery) {
        QueryRewriteResult rewrite = new QueryRewriteResult(rewrittenQuery, rewrittenQuery, rewrittenQuery,
                Map.of(), List.of(rewrittenQuery), 1d, false, "", "legacy-adapter", false);
        RetrievalPlan plan = new RetrievalPlan(domain, RetrievalStrategy.HYBRID,
                List.of(RetrievalBranch.BM25, RetrievalBranch.VECTOR), List.of(),
                40, 40, 160, 30, 30, 6, 1d, 1d,
                "legacy-index", "legacy-policy", "legacy-config", false, "legacy adapter");
        RecallResult recalled = recall(tenantId, plan, rewrite);
        FusionResult fused = fuse(tenantId, plan, recalled);
        RerankResult reranked = rerank(tenantId, plan, rewrite, fused);
        EvidenceValidationResult validated = validate(tenantId, plan, rewrite, reranked);
        return materialize(tenantId, domain, validated.approvedReferences());
    }

    /** 将最终排名转换为可安全进入 Graph State 的轻量引用。 */
    public List<RetrievedChunkRef> references(List<KnowledgeDoc> documents) {
        if (documents == null) return List.of();
        return documents.stream().map(doc -> new RetrievedChunkRef(doc.id(), doc.version(), doc.score())).toList();
    }

    /**
     * 回答节点按轻量引用重新读取当前租户知识。租户、文档版本或有效范围不匹配时直接丢弃，绝不
     * 回退到 checkpoint 的旧内容；政策撤销后，恢复旧 Run 也不会继续使用已失效文本。
     */
    public List<KnowledgeDoc> materialize(String tenantId, String domain, List<RetrievedChunkRef> references) {
        if (references == null || references.isEmpty()) return List.of();
        Map<String, KnowledgeDoc> current = byId(store.knowledge(tenantId, domain));
        List<KnowledgeDoc> result = new ArrayList<>();
        for (RetrievedChunkRef ref : references) {
            KnowledgeDoc doc = current.get(ref.chunkId());
            if (doc == null || !readableByTenant(tenantId, doc.tenantId())
                    || !ref.documentVersion().equals(doc.version()))
                continue;
            result.add(scored(doc, ref.retrievalScore()));
        }
        return List.copyOf(result);
    }

    private RecallResult recallUncached(String tenantId, RetrievalPlan plan,
                                        QueryRewriteResult rewrite, List<KnowledgeDoc> documents) {
        List<DegradationEvent> degradations = Collections.synchronizedList(new ArrayList<>());
        if (elasticsearch.enabled()) {
            try {
                elasticsearch.prepare(documents);
                RecallResult elastic = parallelRecall(tenantId, plan, rewrite, documents, true, degradations);
                if (!elastic.bm25().isEmpty() || !elastic.vector().isEmpty()) return elastic;
                degradations.add(new DegradationEvent("RECALL", "ELASTICSEARCH_EMPTY",
                        "Elasticsearch 两路均未返回候选，继续使用本地受限检索"));
            } catch (ElasticsearchKnowledgeIndex.SearchUnavailableException error) {
                degradations.add(new DegradationEvent("RECALL", "ELASTICSEARCH_UNAVAILABLE", safeMessage(error)));
            }
        }
        return parallelRecall(tenantId, plan, rewrite, documents, false, degradations);
    }

    private RecallResult parallelRecall(String tenantId, RetrievalPlan plan, QueryRewriteResult rewrite,
                                        List<KnowledgeDoc> documents, boolean useElasticsearch,
                                        List<DegradationEvent> degradations) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<List<RankedCandidate>> lexical = plan.uses(RetrievalBranch.BM25)
                    ? CompletableFuture.supplyAsync(() -> useElasticsearch
                                    ? elasticLexical(tenantId, plan, rewrite)
                                    : localLexical(rewrite.lexicalQuery(), documents, plan.lexicalTopK()), executor)
                            .orTimeout(branchTimeoutMillis, TimeUnit.MILLISECONDS)
                    : CompletableFuture.completedFuture(List.of());
            CompletableFuture<List<RankedCandidate>> vector = plan.uses(RetrievalBranch.VECTOR)
                    ? CompletableFuture.supplyAsync(() -> useElasticsearch
                                    ? elasticVector(tenantId, plan, rewrite)
                                    : localVector(tenantId, rewrite.semanticQuery(), documents, plan.vectorTopK()), executor)
                            .orTimeout(branchTimeoutMillis, TimeUnit.MILLISECONDS)
                    : CompletableFuture.completedFuture(List.of());
            CompletableFuture<List<RankedCandidate>> rules = plan.uses(RetrievalBranch.RULE_ENGINE)
                    ? CompletableFuture.supplyAsync(() -> ruleRecall(documents, plan.fusionTopK()), executor)
                    : CompletableFuture.completedFuture(List.of());

            List<RankedCandidate> lexicalResult = joinBranch(lexical, "BM25", degradations);
            List<RankedCandidate> vectorResult = joinBranch(vector, "VECTOR", degradations);
            List<RankedCandidate> ruleResult = joinBranch(rules, "RULE_ENGINE", degradations);

            /*
             * BUSINESS_TOOL 分支只在计划中声明“回答还需要实时业务事实”。订单/物流事实具有独立权限
             * 与工具审计协议，不能伪装成知识 chunk 写进 RRF。它会在后续回答节点通过只读 ToolGateway
             * 注入冻结事实；这里保持空列表，避免把数据库对象泄漏到检索 checkpoint。
             */
            return new RecallResult(lexicalResult, vectorResult, List.of(), ruleResult,
                    List.copyOf(degradations), sourceVersion(documents));
        } finally {
            executor.shutdownNow();
        }
    }

    private List<RankedCandidate> elasticLexical(String tenantId, RetrievalPlan plan,
                                                 QueryRewriteResult rewrite) {
        return elasticsearch.recallLexical(tenantId, plan.domain(), rewrite.lexicalQuery(),
                        plan.documentTypes(), plan.lexicalTopK()).stream()
                .map(hit -> new RankedCandidate(hit.docId(), hit.documentVersion(), RetrievalSource.BM25,
                        hit.rank(), hit.rawScore(), 0d)).toList();
    }

    private List<RankedCandidate> elasticVector(String tenantId, RetrievalPlan plan,
                                                QueryRewriteResult rewrite) {
        float[] queryVector = embeddings.embedRewrittenQuery(tenantId, rewrite.semanticQuery(), "");
        return elasticsearch.recallVector(tenantId, plan.domain(), queryVector, plan.documentTypes(),
                        plan.vectorTopK(), plan.vectorNumCandidates()).stream()
                .map(hit -> new RankedCandidate(hit.docId(), hit.documentVersion(), RetrievalSource.VECTOR,
                        hit.rank(), hit.rawScore(), 0d)).toList();
    }

    private List<RankedCandidate> localLexical(String query, List<KnowledgeDoc> documents, int topK) {
        List<String> queryTerms = terms(query);
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (KnowledgeDoc doc : documents) new HashSet<>(terms(doc.title() + " " + doc.content()))
                .forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        double averageLength = documents.stream().mapToInt(doc -> terms(doc.content()).size())
                .average().orElse(1d);
        List<Scored> scores = new ArrayList<>();
        for (KnowledgeDoc doc : documents) {
            List<String> docTerms = terms(doc.title() + " " + doc.content());
            double score = 0d;
            for (String term : queryTerms) {
                long frequency = docTerms.stream().filter(term::equals).count();
                if (frequency == 0) continue;
                int documentCount = documentFrequency.getOrDefault(term, 0);
                double inverseFrequency = Math.log(1d + (documents.size() - documentCount + .5d)
                        / (documentCount + .5d));
                score += inverseFrequency * (frequency * 2.2d)
                        / (frequency + 1.2d * (.25d + .75d * docTerms.size() / averageLength));
            }
            if (score > 0d) scores.add(new Scored(doc, score));
        }
        scores.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(item -> item.doc().id()));
        return ranked(scores, RetrievalSource.BM25, topK);
    }

    private List<RankedCandidate> localVector(String tenantId, String query,
                                              List<KnowledgeDoc> documents, int topK) {
        float[] queryVector = embeddings.embedRewrittenQuery(tenantId, query, "");
        warmDocumentVectors(documents);
        List<Scored> scores = documents.stream().map(doc ->
                        new Scored(doc, cosine(queryVector, documentVectorCache.getIfPresent(vectorKey(doc)))))
                .filter(item -> item.score() > 0d)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(item -> item.doc().id())).toList();
        return ranked(scores, RetrievalSource.VECTOR, topK);
    }

    /**
     * 规则分支不是第二个语义检索器。它仅用服务端确定的政策类型词和版本排序提取政策候选，确保
     * “退货/维修/补偿”类问题即使措辞非常短，也能把权威政策送入融合层；最终是否适用仍由证据
     * 校验和业务规则决定，不能仅凭这里命中就直接承诺。
     */
    private List<RankedCandidate> ruleRecall(List<KnowledgeDoc> documents, int topK) {
        List<Scored> policies = documents.stream().filter(doc -> isPolicy(doc.title() + " " + doc.domain()))
                .map(doc -> new Scored(doc, policyPriority(doc)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(item -> item.doc().id())).toList();
        return ranked(policies, RetrievalSource.RULE_ENGINE, topK);
    }

    private List<RankedCandidate> ranked(List<Scored> scores, RetrievalSource source, int topK) {
        List<RankedCandidate> result = new ArrayList<>();
        int size = Math.min(Math.max(1, topK), scores.size());
        for (int index = 0; index < size; index++) {
            Scored item = scores.get(index);
            result.add(new RankedCandidate(item.doc().id(), item.doc().version(), source,
                    index + 1, item.score(), 0d));
        }
        return List.copyOf(result);
    }

    private List<RankedCandidate> joinBranch(CompletableFuture<List<RankedCandidate>> future,
                                             String branch, List<DegradationEvent> degradations) {
        try {
            return future.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            String code = cause instanceof java.util.concurrent.TimeoutException
                    ? branch + "_TIMEOUT" : branch + "_UNAVAILABLE";
            degradations.add(new DegradationEvent("RECALL", code, safeMessage(cause)));
            return List.of();
        }
    }

    boolean validRecall(RecallResult recall, String tenantId,
                        Map<String, KnowledgeDoc> currentById,
                        RetrievalPlan plan, String sourceVersion) {
        /*
         * A degraded recall is still a valid result for the current request. The loader marks it
         * DO_NOT_CACHE, so rejecting it here would turn an intentional branch fallback into HTTP 500.
         * Keep this predicate focused on structural and tenant safety; cacheability is controlled by
         * CacheLoadResult.Status.
         */
        if (recall == null || !sourceVersion.equals(recall.sourceVersion())) return false;
        if (recall.bm25().size() > plan.lexicalTopK() || recall.vector().size() > plan.vectorTopK()) return false;
        return allCandidates(recall).stream().allMatch(candidate -> {
            KnowledgeDoc current = currentById.get(candidate.chunkId());
            return current != null && readableByTenant(tenantId, current.tenantId())
                    && candidate.documentVersion().equals(current.version());
        });
    }

    private boolean readableByTenant(String tenantId, String documentTenantId) {
        return tenantId.equals(documentTenantId)
                || TenantService.PLATFORM_TENANT_ID.equals(documentTenantId);
    }

    private List<RankedCandidate> allCandidates(RecallResult recall) {
        List<RankedCandidate> result = new ArrayList<>();
        result.addAll(recall.bm25());
        result.addAll(recall.vector());
        result.addAll(recall.business());
        result.addAll(recall.rules());
        return result;
    }

    private int candidateCount(RecallResult recall) {
        return recall == null ? 0 : allCandidates(recall).size();
    }

    private RecallResult emptyRecall(String sourceVersion) {
        return new RecallResult(List.of(), List.of(), List.of(), List.of(), List.of(), sourceVersion);
    }

    private String sourceVersion(List<KnowledgeDoc> documents) {
        String projection = documents.stream().sorted(Comparator.comparing(KnowledgeDoc::id))
                .map(doc -> doc.id() + "=" + doc.version())
                .reduce((left, right) -> left + "," + right).orElse("none");
        return CacheKeyBuilder.digest(projection);
    }

    private Map<String, KnowledgeDoc> byId(List<KnowledgeDoc> documents) {
        Map<String, KnowledgeDoc> result = new LinkedHashMap<>();
        documents.forEach(doc -> result.put(doc.id(), doc));
        return result;
    }

    private void warmDocumentVectors(List<KnowledgeDoc> documents) {
        List<KnowledgeDoc> missing = documents.stream()
                .filter(doc -> documentVectorCache.getIfPresent(vectorKey(doc)) == null).toList();
        if (missing.isEmpty()) return;
        List<float[]> vectors = embeddings.embedDocumentsForIndex(missing.stream()
                .map(doc -> doc.title() + "\n" + doc.content()).toList());
        if (vectors.size() != missing.size())
            throw new ModelCallException("EmbeddingModel 返回的向量数量与知识文档数量不一致");
        for (int index = 0; index < missing.size(); index++) {
            validateVector(vectors.get(index));
            documentVectorCache.put(vectorKey(missing.get(index)), vectors.get(index));
        }
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length == 0) throw new ModelCallException("EmbeddingModel 返回空向量");
        for (float value : vector) if (!Float.isFinite(value))
            throw new ModelCallException("EmbeddingModel 返回包含 NaN 或 Infinity 的非法向量");
    }

    private String vectorKey(KnowledgeDoc doc) {
        return CacheKeyBuilder.digest(doc.tenantId() + "|" + doc.id() + "|" + doc.version()
                + "|" + embeddings.modelVersion());
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) return 0d;
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0d || rightNorm == 0d ? 0d : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private List<String> terms(String text) {
        String normalized = QueryNormalizer.normalize(text).replaceAll("[^\\p{L}\\p{N}]", "");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < normalized.length(); index++) {
            values.add(String.valueOf(normalized.charAt(index)));
            if (index + 1 < normalized.length()) values.add(normalized.substring(index, index + 2));
        }
        return values;
    }

    private boolean isPolicy(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return containsAny(value, "政策", "规则", "退货", "退款", "换货", "维修", "保修", "补偿", "赔付",
                "after_sale", "complaint", "common");
    }

    private double policyPriority(KnowledgeDoc doc) {
        String text = (doc.title() + " " + doc.domain()).toLowerCase(Locale.ROOT);
        if (containsAny(text, "补偿", "赔付", "compensation")) return 4d;
        if (containsAny(text, "退货", "退款", "换货", "return")) return 3d;
        if (containsAny(text, "维修", "保修", "repair")) return 2d;
        return 1d;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private KnowledgeDoc scored(KnowledgeDoc doc, double score) {
        return new KnowledgeDoc(doc.id(), doc.tenantId(), doc.domain(), doc.title(), doc.content(),
                doc.version(), score);
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank())
            return error == null ? "unknown" : error.getClass().getSimpleName();
        String message = error.getMessage().replaceAll("[\\r\\n\\t]", " ");
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private record Scored(KnowledgeDoc doc, double score) {}
}
