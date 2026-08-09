package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.rag.RagPipelineModels.DocumentType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Elasticsearch 知识检索适配器。
 *
 * <p>写入面向版本化物理索引，读取只走稳定别名。发布新 mapping 或新 embedding 时可以先构建
 * 新物理索引、校验文档数量与抽样查询，再原子切换 read alias；业务代码永远不需要接受用户或
 * 模型给出的索引名。BM25 与向量召回分别返回自己的 rank/rawScore，RRF 在应用层独立完成。</p>
 */
@Component
public final class ElasticsearchKnowledgeIndex {
    private final boolean enabled;
    private final URI baseUrl;
    private final String physicalIndex;
    private final String readAlias;
    private final String writeAlias;
    private final String authorization;
    private final int dimensions;
    private final String embeddingModelVersion;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json;
    private final EmbeddingModel embeddings;
    private final AgentTelemetryService telemetry;
    private final Counter success;
    private final Counter failure;

    /** 防止版本签名集合永久增长；失效后重新同步是安全的幂等操作。 */
    private final Cache<String, Boolean> synchronizedVersions = Caffeine.newBuilder()
            .maximumSize(2_048).expireAfterAccess(Duration.ofHours(6)).build();
    private volatile boolean indexReady;

    public ElasticsearchKnowledgeIndex(
            @Value("${agent.rag.elasticsearch.enabled:false}") boolean enabled,
            @Value("${agent.rag.elasticsearch.url:http://localhost:9200}") URI baseUrl,
            @Value("${agent.rag.elasticsearch.physical-index:${agent.rag.elasticsearch.index:hanaki-knowledge-v1}}") String physicalIndex,
            @Value("${agent.rag.elasticsearch.read-alias:hanaki-knowledge-read}") String readAlias,
            @Value("${agent.rag.elasticsearch.write-alias:hanaki-knowledge-write}") String writeAlias,
            @Value("${agent.rag.elasticsearch.api-key:}") String apiKey,
            @Value("${agent.rag.elasticsearch.username:}") String username,
            @Value("${agent.rag.elasticsearch.password:}") String password,
            @Value("${spring.ai.dashscope.embedding.options.dimensions:512}") int dimensions,
            @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v3}") String embeddingModelVersion,
            @Value("${agent.rag.elasticsearch.timeout-seconds:5}") long timeoutSeconds,
            ObjectMapper json, EmbeddingModel embeddings,
            AgentTelemetryService telemetry, MeterRegistry meters) {
        requireSafeIndexName(physicalIndex, "physical index");
        requireSafeIndexName(readAlias, "read alias");
        requireSafeIndexName(writeAlias, "write alias");
        this.enabled = enabled;
        this.baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", ""));
        this.physicalIndex = physicalIndex;
        this.readAlias = readAlias;
        this.writeAlias = writeAlias;
        this.dimensions = Math.max(1, dimensions);
        this.embeddingModelVersion = embeddingModelVersion == null ? "unknown" : embeddingModelVersion;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.json = json;
        this.embeddings = embeddings;
        this.telemetry = telemetry;
        this.authorization = !apiKey.isBlank() ? "ApiKey " + apiKey
                : !username.isBlank() ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + password)
                .getBytes(StandardCharsets.UTF_8)) : "";
        this.success = meters.counter("agent.rag.elasticsearch.requests", "result", "success");
        this.failure = meters.counter("agent.rag.elasticsearch.requests", "result", "failure");
    }

    public boolean enabled() { return enabled; }

    public String readAlias() { return readAlias; }

    /**
     * 查询前把数据库权威数据同步到写别名。本项目已有事务 Outbox 负责知识发布事件；这里的幂等同步
     * 仍作为本地演示/灾备兜底。生产部署可以由 Outbox 消费者调用同样的版本化写入逻辑，而查询线程
     * 只执行 search。无论哪条路径，文档 ID 都由 tenant + docId 确定，不会跨租户覆盖。
     */
    public void prepare(List<KnowledgeDoc> sourceDocuments) {
        if (!enabled) throw new SearchUnavailableException("Elasticsearch adapter is disabled");
        try {
            ensureIndexAndAliases();
            synchronizeDocuments(sourceDocuments == null ? List.of() : sourceDocuments);
        } catch (RuntimeException error) {
            failure.increment();
            throw unavailable("Elasticsearch preparation failed", error);
        }
    }

    /**
     * Outbox 使用显式可信 tenantId 调用此入口。显式参数让“该租户已经没有任何有效知识”的空集合
     * 仍能删除索引中的全部旧文档；若只从列表第一项推出租户，空集合会导致撤销后的旧政策残留。
     */
    public void prepareTenant(String trustedTenantId, List<KnowledgeDoc> sourceDocuments) {
        if (!enabled) throw new SearchUnavailableException("Elasticsearch adapter is disabled");
        try {
            ensureIndexAndAliases();
            List<KnowledgeDoc> documents = sourceDocuments == null ? List.of() : sourceDocuments;
            if (!trustedTenantId.isBlank() && documents.stream()
                    .anyMatch(doc -> !trustedTenantId.equals(doc.tenantId())))
                throw new SearchUnavailableException("Knowledge synchronization contains cross-tenant documents");
            if (documents.isEmpty() && !trustedTenantId.isBlank()) deleteAllTenantDocuments(trustedTenantId);
            else {
                synchronizeDocuments(documents);
                // Outbox 传入的是租户完整有效集合，必须同时清理已经完全移除的旧 domain。
                deleteStaleTenantDocuments(trustedTenantId, documents);
            }
        } catch (RuntimeException error) {
            failure.increment();
            throw unavailable("Elasticsearch preparation failed", error);
        }
    }

    /** 独立 BM25 召回。lexicalQuery 与向量查询文本不共用，便于保留 SKU/型号等精确词。 */
    public List<BranchHit> recallLexical(String tenantId, String domain, String lexicalQuery,
                                         List<DocumentType> documentTypes, int topK) {
        requireEnabled();
        return observe("rag.elasticsearch.bm25", tenantId, domain, () -> {
            Map<String, Object> multiMatch = new LinkedHashMap<>();
            multiMatch.put("query", lexicalQuery == null ? "" : lexicalQuery);
            multiMatch.put("fields", List.of("title^5", "aliases^4", "summary^1.5", "content"));
            multiMatch.put("type", "best_fields");
            multiMatch.put("operator", "or");
            Map<String, Object> body = Map.of(
                    "size", Math.max(1, topK),
                    "_source", List.of("docId", "version"),
                    "query", Map.of("bool", Map.of(
                            "filter", tenantFilters(tenantId, domain, documentTypes),
                            "must", List.of(Map.of("multi_match", multiMatch)))));
            return searchRequest(body);
        });
    }

    /**
     * 独立 kNN 召回。tenant/status/domain/effective/documentType 全部放进 kNN pre-filter，必须在近邻
     * 候选生成前生效；若只在 Java 结果端过滤，其他租户或过期文档会先占满 num_candidates，造成
     * 召回缺失，并把本不应参与检索的文档暴露给搜索集群的评分过程。
     */
    public List<BranchHit> recallVector(String tenantId, String domain, float[] vector,
                                        List<DocumentType> documentTypes, int topK, int numCandidates) {
        requireEnabled();
        validateVector(vector);
        return observe("rag.elasticsearch.vector", tenantId, domain, () -> {
            Map<String, Object> knn = new LinkedHashMap<>();
            knn.put("field", "embedding");
            knn.put("query_vector", vector);
            knn.put("k", Math.max(1, topK));
            knn.put("num_candidates", Math.max(Math.max(1, topK), numCandidates));
            knn.put("filter", Map.of("bool", Map.of(
                    "filter", tenantFilters(tenantId, domain, documentTypes))));
            return searchRequest(Map.of("size", Math.max(1, topK),
                    "_source", List.of("docId", "version"), "knn", knn));
        });
    }

    /**
     * 保留旧调用契约用于兼容非子图调用方。实现内部仍然并行获得两张排名表并按 rank 做 RRF，
     * 不会把 BM25 _score 与 cosine score 直接相加。
     */
    public List<KnowledgeDoc> search(String tenantId, String domain, String query,
                                     List<KnowledgeDoc> sourceDocs, int limit, int rrfK) {
        return search(tenantId, domain, query, embeddings.embed(query == null ? "" : query),
                sourceDocs, limit, rrfK);
    }

    public List<KnowledgeDoc> search(String tenantId, String domain, String query, float[] queryVector,
                                     List<KnowledgeDoc> sourceDocs, int limit, int rrfK) {
        prepare(sourceDocs);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<BranchHit>> lexical = CompletableFuture.supplyAsync(
                    () -> recallLexical(tenantId, domain, query, List.of(), Math.max(limit * 3, limit)), executor);
            CompletableFuture<List<BranchHit>> vector = CompletableFuture.supplyAsync(
                    () -> recallVector(tenantId, domain, queryVector, List.of(),
                            Math.max(limit * 3, limit), Math.max(limit * 12, 50)), executor);
            List<BranchHit> lexicalHits = lexical.join();
            List<BranchHit> vectorHits = vector.join();
            Map<String, Double> fused = new HashMap<>();
            for (int index = 0; index < lexicalHits.size(); index++)
                fused.merge(lexicalHits.get(index).docId(), 1d / (Math.max(1, rrfK) + index + 1), Double::sum);
            for (int index = 0; index < vectorHits.size(); index++)
                fused.merge(vectorHits.get(index).docId(), 1d / (Math.max(1, rrfK) + index + 1), Double::sum);
            Map<String, KnowledgeDoc> byId = new HashMap<>();
            sourceDocs.forEach(doc -> byId.put(doc.id(), doc));
            return fused.entrySet().stream().filter(entry -> byId.containsKey(entry.getKey()))
                    .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                    .limit(Math.max(1, limit)).map(entry -> scored(byId.get(entry.getKey()), entry.getValue())).toList();
        }
    }

    private synchronized void ensureIndexAndAliases() {
        if (indexReady) return;
        HttpResponse<String> head = request("HEAD", "/" + physicalIndex, null, "application/json");
        if (head.statusCode() == 404) createPhysicalIndex();
        else require2xx(head, "check physical knowledge index");
        ensureAliases();
        indexReady = true;
    }

    private void createPhysicalIndex() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("docId", Map.of("type", "keyword"));
        properties.put("knowledgeId", Map.of("type", "keyword"));
        properties.put("chunkId", Map.of("type", "keyword"));
        properties.put("parentDocumentId", Map.of("type", "keyword"));
        properties.put("tenantId", Map.of("type", "keyword"));
        properties.put("domain", Map.of("type", "keyword"));
        properties.put("documentType", Map.of("type", "keyword"));
        properties.put("publishStatus", Map.of("type", "keyword"));
        properties.put("lifecycleStatus", Map.of("type", "keyword"));
        properties.put("effectiveAt", Map.of("type", "date"));
        properties.put("expiredAt", Map.of("type", "date"));
        properties.put("version", Map.of("type", "keyword"));
        properties.put("contentHash", Map.of("type", "keyword"));
        properties.put("embeddingModelVersion", Map.of("type", "keyword"));
        properties.put("title", Map.of("type", "text", "analyzer", "standard"));
        properties.put("aliases", Map.of("type", "text", "analyzer", "standard"));
        properties.put("summary", Map.of("type", "text", "analyzer", "standard"));
        properties.put("content", Map.of("type", "text", "analyzer", "standard"));
        properties.put("embedding", Map.of("type", "dense_vector", "dims", dimensions,
                "index", true, "similarity", "cosine"));
        Map<String, Object> mappings = new LinkedHashMap<>();
        mappings.put("dynamic", "strict");
        mappings.put("properties", properties);
        HttpResponse<String> create = request("PUT", "/" + physicalIndex,
                jsonValue(Map.of("mappings", mappings)), "application/json");
        if (create.statusCode() == 400) {
            // 多实例冷启动可能同时创建；二次 HEAD 成功即可继续，其他 400 仍会被识别为失败。
            require2xx(request("HEAD", "/" + physicalIndex, null, "application/json"),
                    "verify physical knowledge index");
        } else require2xx(create, "create physical knowledge index");
    }

    private void ensureAliases() {
        boolean readExists = aliasExists(readAlias);
        boolean writeExists = aliasExists(writeAlias);
        if (readExists && writeExists) return;
        List<Map<String, Object>> actions = new ArrayList<>();
        if (!readExists) actions.add(Map.of("add", Map.of("index", physicalIndex, "alias", readAlias)));
        if (!writeExists) actions.add(Map.of("add", Map.of("index", physicalIndex, "alias", writeAlias,
                "is_write_index", true)));
        require2xx(request("POST", "/_aliases", jsonValue(Map.of("actions", actions)), "application/json"),
                "create knowledge aliases");
    }

    private boolean aliasExists(String alias) {
        // 允许显式把 alias 配成物理索引，便于兼容旧环境；默认配置仍使用独立读写别名。
        if (alias.equals(physicalIndex)) return true;
        HttpResponse<String> response = request("HEAD", "/_alias/" + alias, null, "application/json");
        if (response.statusCode() == 404) return false;
        require2xx(response, "check alias " + alias);
        return true;
    }

    private void synchronizeDocuments(List<KnowledgeDoc> docs) {
        if (docs.isEmpty()) return;
        String versionKey = docs.stream().map(doc -> doc.tenantId() + ":" + doc.id() + ":" + doc.version())
                .sorted().reduce((left, right) -> left + "|" + right).orElse("empty");
        String signature = sha256(versionKey + "|" + physicalIndex + "|" + embeddingModelVersion);
        if (synchronizedVersions.getIfPresent(signature) != null) return;
        try {
            List<float[]> vectors = embeddings.embed(docs.stream()
                    .map(doc -> doc.title() + "\n" + doc.content()).toList());
            if (vectors.size() != docs.size())
                throw new SearchUnavailableException("Embedding count mismatch during indexing");
            StringBuilder ndjson = new StringBuilder();
            for (int index = 0; index < docs.size(); index++) {
                KnowledgeDoc doc = docs.get(index);
                validateVector(vectors.get(index));
                ndjson.append(jsonValue(Map.of("index", Map.of("_index", writeAlias, "_id", elasticId(doc)))))
                        .append('\n');
                ndjson.append(jsonValue(indexBody(doc, vectors.get(index)))).append('\n');
            }
            HttpResponse<String> response = request("POST", "/_bulk?refresh=wait_for", ndjson.toString(),
                    "application/x-ndjson");
            require2xx(response, "bulk index knowledge");
            if (jsonTree(response.body()).path("errors").asBoolean(false))
                throw new SearchUnavailableException("Elasticsearch bulk response contains failed items");
            deleteStaleDocuments(docs);
            synchronizedVersions.put(signature, Boolean.TRUE);
        } catch (RuntimeException error) {
            synchronizedVersions.invalidate(signature);
            throw error;
        }
    }

    private Map<String, Object> indexBody(KnowledgeDoc doc, float[] vector) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", doc.id());
        body.put("knowledgeId", doc.id());
        body.put("chunkId", doc.id());
        body.put("parentDocumentId", doc.id());
        body.put("tenantId", doc.tenantId());
        body.put("domain", doc.domain());
        body.put("documentType", inferDocumentType(doc).name());
        body.put("publishStatus", "PUBLISHED");
        body.put("lifecycleStatus", "ACTIVE");
        body.put("effectiveAt", Instant.EPOCH.toString());
        body.put("version", doc.version());
        body.put("contentHash", sha256(doc.title() + "\n" + doc.content()));
        body.put("embeddingModelVersion", embeddingModelVersion);
        body.put("title", doc.title());
        body.put("aliases", doc.title());
        body.put("summary", summarize(doc.content()));
        body.put("content", doc.content());
        body.put("embedding", vector);
        return body;
    }

    /** 删除权威集合外的旧文档，避免旧版本先占满 Top-K 后才在 Java 层被过滤。 */
    private void deleteStaleDocuments(List<KnowledgeDoc> docs) {
        String tenantId = docs.getFirst().tenantId();
        List<String> domains = docs.stream().map(KnowledgeDoc::domain).distinct().toList();
        List<String> activeIds = docs.stream().map(this::elasticId).distinct().toList();
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", List.of(Map.of("term", Map.of("tenantId", tenantId)),
                Map.of("terms", Map.of("domain", domains))));
        bool.put("must_not", List.of(Map.of("ids", Map.of("values", activeIds))));
        HttpResponse<String> response = request("POST",
                "/" + writeAlias + "/_delete_by_query?refresh=true&conflicts=proceed",
                jsonValue(Map.of("query", Map.of("bool", bool))), "application/json");
        require2xx(response, "delete stale knowledge");
    }

    private void deleteAllTenantDocuments(String tenantId) {
        HttpResponse<String> response = request("POST",
                "/" + writeAlias + "/_delete_by_query?refresh=true&conflicts=proceed",
                jsonValue(Map.of("query", Map.of("term", Map.of("tenantId", tenantId)))), "application/json");
        require2xx(response, "delete all stale tenant knowledge");
    }

    private void deleteStaleTenantDocuments(String tenantId, List<KnowledgeDoc> activeDocuments) {
        List<String> activeIds = activeDocuments.stream().map(this::elasticId).distinct().toList();
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", List.of(Map.of("term", Map.of("tenantId", tenantId))));
        bool.put("must_not", List.of(Map.of("ids", Map.of("values", activeIds))));
        HttpResponse<String> response = request("POST",
                "/" + writeAlias + "/_delete_by_query?refresh=true&conflicts=proceed",
                jsonValue(Map.of("query", Map.of("bool", bool))), "application/json");
        require2xx(response, "delete tenant-wide stale knowledge");
    }

    private List<Object> tenantFilters(String tenantId, String domain, List<DocumentType> documentTypes) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("tenantId", tenantId)));
        filters.add(Map.of("term", Map.of("publishStatus", "PUBLISHED")));
        filters.add(Map.of("term", Map.of("lifecycleStatus", "ACTIVE")));
        filters.add(Map.of("terms", Map.of("domain", List.of(domain, "COMMON"))));
        filters.add(Map.of("range", Map.of("effectiveAt", Map.of("lte", "now"))));
        if (documentTypes != null && !documentTypes.isEmpty())
            filters.add(Map.of("terms", Map.of("documentType", documentTypes.stream().map(Enum::name).toList())));

        // expiredAt 为空表示长期有效；有值时必须晚于当前时刻。
        filters.add(Map.of("bool", Map.of("should", List.of(
                Map.of("bool", Map.of("must_not", Map.of("exists", Map.of("field", "expiredAt")))),
                Map.of("range", Map.of("expiredAt", Map.of("gt", "now")))),
                "minimum_should_match", 1)));
        return List.copyOf(filters);
    }

    private List<BranchHit> searchRequest(Map<String, Object> body) {
        HttpResponse<String> response = request("POST", "/" + readAlias + "/_search",
                jsonValue(body), "application/json");
        require2xx(response, "search knowledge by read alias");
        List<BranchHit> hits = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : jsonTree(response.body()).path("hits").path("hits")) {
            String docId = item.path("_source").path("docId").asText("");
            String version = item.path("_source").path("version").asText("");
            double score = item.path("_score").asDouble(0d);
            if (!docId.isBlank() && Double.isFinite(score)) hits.add(new BranchHit(docId, version, rank++, score));
        }
        success.increment();
        return List.copyOf(hits);
    }

    private <T> T observe(String operation, String tenantId, String domain,
                          java.util.function.Supplier<T> action) {
        try {
            return telemetry.observeCurrent(operation, "RETRIEVAL",
                    Map.of("tenant", telemetry.scopedKey(tenantId), "domain", domain),
                    Map.of("readAlias", readAlias, "physicalIndex", physicalIndex), action);
        } catch (RuntimeException error) {
            failure.increment();
            throw unavailable(operation + " failed", error);
        }
    }

    private HttpResponse<String> request(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(timeout)
                    .header("Accept", "application/json");
            if (!authorization.isBlank()) builder.header("Authorization", authorization);
            if (body != null) builder.header("Content-Type", contentType);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) {
            throw new SearchUnavailableException("Elasticsearch request failed: " + path, error);
        }
    }

    private DocumentType inferDocumentType(KnowledgeDoc doc) {
        String text = (doc.domain() + " " + doc.title()).toLowerCase(Locale.ROOT);
        if (containsAny(text, "维修", "保修", "repair")) return DocumentType.REPAIR_POLICY;
        if (containsAny(text, "补偿", "赔付", "投诉", "compensation")) return DocumentType.COMPENSATION_POLICY;
        if (containsAny(text, "退货", "退款", "换货", "return")) return DocumentType.RETURN_POLICY;
        if (containsAny(text, "活动", "优惠", "促销", "promotion")) return DocumentType.PROMOTION_POLICY;
        if (containsAny(text, "参数", "规格", "spec")) return DocumentType.PRODUCT_SPECIFICATION;
        if (containsAny(text, "说明书", "使用", "manual")) return DocumentType.PRODUCT_MANUAL;
        return DocumentType.PRODUCT_FAQ;
    }

    private String summarize(String content) {
        if (content == null) return "";
        String value = content.strip();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != dimensions)
            throw new SearchUnavailableException("Embedding dimension does not match index mapping");
        for (float value : vector) if (!Float.isFinite(value))
            throw new SearchUnavailableException("Embedding contains NaN or Infinity");
    }

    private void requireEnabled() {
        if (!enabled) throw new SearchUnavailableException("Elasticsearch adapter is disabled");
    }

    private void requireSafeIndexName(String value, String label) {
        if (value == null || !value.matches("[a-z0-9._-]+"))
            throw new IllegalArgumentException("Unsafe Elasticsearch " + label + " name");
    }

    private void require2xx(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new SearchUnavailableException(operation + " returned HTTP " + response.statusCode());
    }

    private KnowledgeDoc scored(KnowledgeDoc doc, double score) {
        return new KnowledgeDoc(doc.id(), doc.tenantId(), doc.domain(), doc.title(), doc.content(),
                doc.version(), score);
    }

    private String elasticId(KnowledgeDoc doc) {
        return URLEncoder.encode(doc.tenantId() + "::" + doc.id(), StandardCharsets.UTF_8);
    }

    private String jsonValue(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new SearchUnavailableException("Cannot encode Elasticsearch request", error); }
    }

    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot hash knowledge version", error);
        }
    }

    private JsonNode jsonTree(String value) {
        try { return json.readTree(value); }
        catch (Exception error) { throw new SearchUnavailableException("Cannot decode Elasticsearch response", error); }
    }

    private SearchUnavailableException unavailable(String message, RuntimeException error) {
        return error instanceof SearchUnavailableException unavailable ? unavailable
                : new SearchUnavailableException(message, error);
    }

    public record BranchHit(String docId, String documentVersion, int rank, double rawScore) {}

    public static final class SearchUnavailableException extends RuntimeException {
        SearchUnavailableException(String message) { super(message); }
        SearchUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
