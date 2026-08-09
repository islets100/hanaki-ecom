package com.hanaki.ecom.rag;

import com.hanaki.ecom.context.RetrievedChunkRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 子图在各节点之间传递的结构化、轻量状态。
 *
 * <p>这里刻意不保存知识正文、向量和 Elasticsearch 原始响应。Graph checkpoint 可能被长期保存、
 * 重放或交给运维人员排查，如果把正文放进状态，不但会显著放大 checkpoint，还会让已经撤销的政策
 * 在恢复旧运行时重新进入模型上下文。因此所有候选都只保留 chunkId、文档版本、来源、名次和分数；
 * 真正生成答案前必须再次按当前租户和当前版本从可信数据源物化正文。</p>
 */
public final class RagPipelineModels {
    private RagPipelineModels() {}

    /** 允许 Query Router 选择的固定召回分支；模型不能返回任意类名、URL 或脚本。 */
    public enum RetrievalBranch {
        BM25,
        VECTOR,
        BUSINESS_TOOL,
        RULE_ENGINE
    }

    /**
     * 文档逻辑类型。它既用于路由，也会成为服务端生成的过滤条件。
     * 枚举值由应用发布，不能由用户输入直接拼接成索引名或查询 DSL。
     */
    public enum DocumentType {
        PRODUCT_MANUAL,
        PRODUCT_SPECIFICATION,
        PRODUCT_FAQ,
        PROMOTION_POLICY,
        RETURN_POLICY,
        REPAIR_POLICY,
        COMPENSATION_POLICY
    }

    /** 检索策略是路由结果的稳定协议，便于审计、灰度和统计降级比例。 */
    public enum RetrievalStrategy {
        LEXICAL_ONLY,
        HYBRID,
        BUSINESS_ONLY,
        HYBRID_WITH_BUSINESS,
        HYBRID_WITH_RULE
    }

    /** 候选的真实来源。RRF 只使用名次，不把不同来源不可比的原始分数直接相加。 */
    public enum RetrievalSource {
        BM25,
        VECTOR,
        BUSINESS_TOOL,
        RULE_ENGINE,
        RRF_FALLBACK
    }

    /**
     * Query Rewrite 的完整结构化结果。
     *
     * @param standaloneQuestion 脱离对话也能理解的问题
     * @param lexicalQuery       为 BM25 保留型号、编号及关键术语的查询
     * @param semanticQuery      为向量检索组织的自然语言查询
     * @param protectedEntities  从可信实体抽取节点继承的实体；改写不得删除或篡改
     * @param subQuestions       复杂问题拆出的子问题，用于后续证据覆盖检查
     * @param confidence         改写可信度，范围为 0~1
     * @param clarificationRequired 是否必须先向用户澄清指代或关键条件
     * @param clarificationReason   需要澄清的可解释原因
     * @param modelVersion       生成此次改写的模型/回退策略版本
     * @param fallbackUsed       模型失败或输出非法时是否使用了保守回退
     */
    public record QueryRewriteResult(
            String standaloneQuestion,
            String lexicalQuery,
            String semanticQuery,
            Map<String, String> protectedEntities,
            List<String> subQuestions,
            double confidence,
            boolean clarificationRequired,
            String clarificationReason,
            String modelVersion,
            boolean fallbackUsed) {

        public QueryRewriteResult {
            standaloneQuestion = safe(standaloneQuestion);
            lexicalQuery = safe(lexicalQuery);
            semanticQuery = safe(semanticQuery);
            protectedEntities = protectedEntities == null ? Map.of() : Map.copyOf(protectedEntities);
            subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
            confidence = Double.isFinite(confidence) ? Math.max(0d, Math.min(1d, confidence)) : 0d;
            clarificationReason = safe(clarificationReason);
            modelVersion = safe(modelVersion);
        }
    }

    /**
     * 应用端审核后的可信检索计划。tenantId、用户身份和物理索引名不属于这个对象，调用方必须从
     * 已认证上下文和服务端配置获取，防止模型输出越权租户或把任意字符串当作索引名。
     */
    public record RetrievalPlan(
            String domain,
            RetrievalStrategy strategy,
            List<RetrievalBranch> branches,
            List<DocumentType> documentTypes,
            int lexicalTopK,
            int vectorTopK,
            int vectorNumCandidates,
            int fusionTopK,
            int rerankTopN,
            int finalTopK,
            double lexicalWeight,
            double vectorWeight,
            String indexVersion,
            String policyVersion,
            String configVersion,
            boolean skipKnowledge,
            String routingReason) {

        public RetrievalPlan {
            domain = safe(domain);
            branches = enumList(branches, RetrievalBranch.class);
            documentTypes = enumList(documentTypes, DocumentType.class);
            lexicalTopK = positive(lexicalTopK);
            vectorTopK = positive(vectorTopK);
            vectorNumCandidates = Math.max(vectorTopK, positive(vectorNumCandidates));
            fusionTopK = positive(fusionTopK);
            rerankTopN = positive(rerankTopN);
            finalTopK = positive(finalTopK);
            lexicalWeight = validWeight(lexicalWeight);
            vectorWeight = validWeight(vectorWeight);
            indexVersion = safe(indexVersion);
            policyVersion = safe(policyVersion);
            configVersion = safe(configVersion);
            routingReason = safe(routingReason);
        }

        public boolean uses(RetrievalBranch branch) {
            return !skipKnowledge && branches.contains(branch);
        }
    }

    /** 单路召回候选；rawScore 仅用于该分支诊断，跨分支融合只能使用 rank。 */
    public record RankedCandidate(
            String chunkId,
            String documentVersion,
            RetrievalSource source,
            int rank,
            double rawScore,
            double fusedScore) {

        public RankedCandidate {
            chunkId = safe(chunkId);
            documentVersion = safe(documentVersion);
            rank = Math.max(1, rank);
            rawScore = Double.isFinite(rawScore) ? rawScore : 0d;
            fusedScore = Double.isFinite(fusedScore) ? fusedScore : 0d;
        }

        public RankedCandidate withFusedScore(double score) {
            return new RankedCandidate(chunkId, documentVersion, source, rank, rawScore, score);
        }
    }

    /** 可观测的降级事件。发生降级的结果仍可服务当前请求，但不得写入长期检索缓存。 */
    public record DegradationEvent(String stage, String code, String detail) {
        public DegradationEvent {
            stage = safe(stage);
            code = safe(code);
            detail = safe(detail);
        }
    }

    /** 并行召回节点输出；每一路保持独立列表，便于正确执行 RRF 和排查单路异常。 */
    public record RecallResult(
            List<RankedCandidate> bm25,
            List<RankedCandidate> vector,
            List<RankedCandidate> business,
            List<RankedCandidate> rules,
            List<DegradationEvent> degradations,
            String sourceVersion) {

        public RecallResult {
            bm25 = rankedCandidates(bm25);
            vector = rankedCandidates(vector);
            business = rankedCandidates(business);
            rules = rankedCandidates(rules);
            degradations = degradationEvents(degradations);
            sourceVersion = safe(sourceVersion);
        }

        public boolean degraded() { return !degradations.isEmpty(); }
    }

    /** RRF 节点输出，候选已按融合分数降序排列并完成 chunkId/内容摘要去重。 */
    public record FusionResult(
            List<RankedCandidate> candidates,
            List<DegradationEvent> degradations,
            String algorithmVersion) {

        public FusionResult {
            candidates = rankedCandidates(candidates);
            degradations = degradationEvents(degradations);
            algorithmVersion = safe(algorithmVersion);
        }
    }

    /** Rerank 节点输出仍然只含轻量引用，不允许外部模型返回任意正文或任意文档 ID。 */
    public record RerankResult(
            List<RetrievedChunkRef> references,
            List<DegradationEvent> degradations,
            String modelVersion) {

        public RerankResult {
            references = retrievedChunkRefs(references);
            degradations = degradationEvents(degradations);
            modelVersion = safe(modelVersion);
        }
    }

    /**
     * 证据校验的最终决定。approvedReferences 才能进入回答节点；insufficient/conflict 为 true 时，
     * 回答节点必须采用澄清、保守说明或人工处理，而不是让模型凭常识补齐政策。
     */
    public record EvidenceValidationResult(
            List<RetrievedChunkRef> approvedReferences,
            boolean insufficient,
            boolean conflict,
            List<String> uncoveredSubQuestions,
            List<String> reasons,
            List<DegradationEvent> degradations) {

        public EvidenceValidationResult {
            approvedReferences = retrievedChunkRefs(approvedReferences);
            uncoveredSubQuestions = uncoveredSubQuestions == null ? List.of() : List.copyOf(uncoveredSubQuestions);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            degradations = degradationEvents(degradations);
        }
    }

    private static List<RankedCandidate> rankedCandidates(List<?> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<RankedCandidate> result = new ArrayList<>(source.size());
        for (Object value : source) {
            if (value instanceof RankedCandidate candidate) {
                result.add(candidate);
                continue;
            }
            Map<?, ?> fields = checkpointMap(value, RankedCandidate.class);
            result.add(new RankedCandidate(
                    stringField(fields, "chunkId", RankedCandidate.class),
                    stringField(fields, "documentVersion", RankedCandidate.class),
                    enumValue(fields.get("source"), RetrievalSource.class),
                    intField(fields, "rank", RankedCandidate.class),
                    doubleField(fields, "rawScore", RankedCandidate.class),
                    doubleField(fields, "fusedScore", RankedCandidate.class)));
        }
        return List.copyOf(result);
    }

    private static List<DegradationEvent> degradationEvents(List<?> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<DegradationEvent> result = new ArrayList<>(source.size());
        for (Object value : source) {
            if (value instanceof DegradationEvent event) {
                result.add(event);
                continue;
            }
            Map<?, ?> fields = checkpointMap(value, DegradationEvent.class);
            result.add(new DegradationEvent(
                    stringField(fields, "stage", DegradationEvent.class),
                    stringField(fields, "code", DegradationEvent.class),
                    stringField(fields, "detail", DegradationEvent.class)));
        }
        return List.copyOf(result);
    }

    private static List<RetrievedChunkRef> retrievedChunkRefs(List<?> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<RetrievedChunkRef> result = new ArrayList<>(source.size());
        for (Object value : source) {
            if (value instanceof RetrievedChunkRef reference) {
                result.add(reference);
                continue;
            }
            Map<?, ?> fields = checkpointMap(value, RetrievedChunkRef.class);
            result.add(new RetrievedChunkRef(
                    stringField(fields, "chunkId", RetrievedChunkRef.class),
                    stringField(fields, "documentVersion", RetrievedChunkRef.class),
                    doubleField(fields, "retrievalScore", RetrievedChunkRef.class)));
        }
        return List.copyOf(result);
    }

    /**
     * Graph checkpoint restoration can retain the record type while materializing enum list elements
     * as their JSON names. Normalize those names at the record boundary so later checkpoints never
     * receive a List whose runtime element types violate the declared generic contract.
     */
    private static <E extends Enum<E>> List<E> enumList(List<?> source, Class<E> enumType) {
        if (source == null || source.isEmpty()) return List.of();
        List<E> result = new ArrayList<>(source.size());
        for (Object value : source) {
            result.add(enumValue(value, enumType));
        }
        return List.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(Object value, Class<E> enumType) {
        if (enumType.isInstance(value)) return enumType.cast(value);
        if (value instanceof String name) {
            try {
                return Enum.valueOf(enumType, name.trim());
            } catch (IllegalArgumentException ignored) {
                // Fall through to the fail-closed error below.
            }
        }
        throw invalidCheckpointValue(enumType, null, value);
    }

    private static Map<?, ?> checkpointMap(Object value, Class<?> expectedType) {
        if (value instanceof Map<?, ?> fields) return fields;
        throw invalidCheckpointValue(expectedType, null, value);
    }

    private static String stringField(Map<?, ?> fields, String field, Class<?> expectedType) {
        Object value = fields.get(field);
        if (value instanceof String text) return text;
        throw invalidCheckpointValue(expectedType, field, value);
    }

    private static int intField(Map<?, ?> fields, String field, Class<?> expectedType) {
        Object value = fields.get(field);
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            if (Double.isFinite(numericValue) && numericValue == Math.rint(numericValue)
                    && numericValue >= Integer.MIN_VALUE && numericValue <= Integer.MAX_VALUE) {
                return number.intValue();
            }
        }
        throw invalidCheckpointValue(expectedType, field, value);
    }

    private static double doubleField(Map<?, ?> fields, String field, Class<?> expectedType) {
        Object value = fields.get(field);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw invalidCheckpointValue(expectedType, field, value);
    }

    private static IllegalArgumentException invalidCheckpointValue(
            Class<?> expectedType, String field, Object value) {
        String location = field == null ? expectedType.getSimpleName() : expectedType.getSimpleName() + "." + field;
        return new IllegalArgumentException("Invalid " + location + " checkpoint value: " + String.valueOf(value));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }

    private static double validWeight(double value) {
        return Double.isFinite(value) && value > 0d ? value : 1d;
    }
}
