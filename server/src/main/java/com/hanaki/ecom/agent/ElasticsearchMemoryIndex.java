package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.memory.domain.MemoryTrustLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 情景记忆检索投影。
 *
 * <p>MySQL 中的 episodic_memory 才是权威记录；本索引可以删除并重建。tenantId、userId、agentType、
 * status 和两种有效期都在 kNN 近似检索前作为 pre-filter 执行，绝不能先全库向量召回再在 Java
 * 中过滤身份。所有过滤值来自服务端 MemoryScope，模型和 HTTP Body 无权指定。</p>
 */
@Component
public final class ElasticsearchMemoryIndex {
    private final boolean enabled;
    private final URI baseUrl;
    private final String index;
    private final int dimensions;
    private final Duration timeout;
    private final String authorization;
    private final HttpClient http;
    private final ObjectMapper json;
    private final AgentTelemetryService telemetry;
    private final Counter success;
    private final Counter failure;
    private volatile boolean indexReady;

    public ElasticsearchMemoryIndex(
            @Value("${agent.memory.elasticsearch.enabled:${agent.rag.elasticsearch.enabled:false}}") boolean enabled,
            @Value("${agent.memory.elasticsearch.url:${agent.rag.elasticsearch.url:http://localhost:9200}}") URI baseUrl,
            @Value("${agent.memory.elasticsearch.index:hanaki-memory-v2}") String index,
            @Value("${agent.rag.elasticsearch.api-key:}") String apiKey,
            @Value("${agent.rag.elasticsearch.username:}") String username,
            @Value("${agent.rag.elasticsearch.password:}") String password,
            @Value("${spring.ai.dashscope.embedding.options.dimensions:512}") int dimensions,
            @Value("${agent.memory.elasticsearch.timeout-seconds:5}") long timeoutSeconds,
            ObjectMapper json, AgentTelemetryService telemetry, MeterRegistry meters) {
        if (!index.matches("[a-z0-9._-]+")) throw new IllegalArgumentException("Unsafe memory index name");
        this.enabled = enabled;
        this.baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", ""));
        this.index = index;
        this.dimensions = dimensions;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.json = json;
        this.telemetry = telemetry;
        this.authorization = !apiKey.isBlank() ? "ApiKey " + apiKey
                : !username.isBlank() ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + password)
                .getBytes(StandardCharsets.UTF_8)) : "";
        this.success = meters.counter("agent.memory.elasticsearch.requests", "result", "success");
        this.failure = meters.counter("agent.memory.elasticsearch.requests", "result", "failure");
    }

    public boolean enabled() { return enabled; }

    public void index(String tenantId, String userId, String agentType, String contentHash, String content,
                      float[] vector, double importance, MemoryTrustLevel trustLevel, String sourceType,
                      long version, Instant occurredAt, Instant promptEligibleUntil) {
        if (!enabled) return;
        requireScope(tenantId, userId, agentType);
        try {
            telemetry.observeCurrent("memory.elasticsearch.index", "MEMORY",
                    Map.of("tenant", telemetry.scopedKey(tenantId), "user", telemetry.scopedKey(userId)),
                    Map.of("index", index), () -> {
                        ensureIndex();
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("memoryId", contentHash); body.put("tenantId", tenantId); body.put("userId", userId);
                        body.put("agentType", agentType); body.put("contentHash", contentHash);
                        body.put("content", content); body.put("embedding", vector); body.put("importance", importance);
                        body.put("confidence", importance); body.put("trustLevel", trustLevel.name());
                        body.put("sourceType", sourceType); body.put("version", version); body.put("status", "ACTIVE");
                        body.put("occurredAt", occurredAt.toString());
                        body.put("expiresAt", promptEligibleUntil.toString());
                        body.put("promptEligibleUntil", promptEligibleUntil.toString());
                        require2xx(request("PUT", "/" + index + "/_doc/" + documentId(tenantId, userId, contentHash),
                                encode(body)), "index episodic memory");
                        return true;
                    });
            success.increment();
        } catch (RuntimeException error) {
            failure.increment();
            throw new MemoryIndexUnavailableException("Elasticsearch memory index failed", error);
        }
    }

    /** 迁移兼容入口；旧调用没有业务领域，只能进入 UNKNOWN 隔离分区。 */
    public void index(String tenantId, String userId, String contentHash, String content, float[] vector,
                      double importance, Instant createdAt, Instant expiresAt) {
        index(tenantId, userId, "UNKNOWN", contentHash, content, vector, importance,
                MemoryTrustLevel.USER_CONFIRMED, "USER_CONFIRMATION", 1, createdAt, expiresAt);
    }

    public List<MemoryHit> search(String tenantId, String userId, String agentType,
                                  float[] queryVector, int topK) {
        if (!enabled) return List.of();
        requireScope(tenantId, userId, agentType);
        try {
            List<MemoryHit> result = telemetry.observeCurrent("memory.elasticsearch.search", "RETRIEVAL",
                    Map.of("tenant", telemetry.scopedKey(tenantId), "user", telemetry.scopedKey(userId)),
                    Map.of("index", index, "strategy", "knn+recency+importance"),
                    () -> doSearch(tenantId, userId, agentType, queryVector, topK));
            success.increment();
            return result;
        } catch (RuntimeException error) {
            failure.increment();
            throw new MemoryIndexUnavailableException("Elasticsearch memory search failed", error);
        }
    }

    /** 迁移兼容入口，只读取 UNKNOWN 分区，不能绕开业务领域过滤。 */
    public List<MemoryHit> search(String tenantId, String userId, float[] queryVector, int topK) {
        return search(tenantId, userId, "UNKNOWN", queryVector, topK);
    }

    /**
     * 删除用户已撤销画像的检索投影。文档 ID 同时包含 tenant/user/contentHash，因此即使收到重复
     * Outbox，DELETE 404 也可以安全视为成功；绝不按裸 contentHash 删除其他用户文档。
     */
    public void delete(String tenantId, String userId, String contentHash) {
        if (!enabled) return;
        requireScope(tenantId, userId, "PROFILE_DELETE");
        try {
            HttpResponse<String> response = request("DELETE",
                    "/" + index + "/_doc/" + documentId(tenantId, userId, contentHash), null);
            if (response.statusCode() != 404) require2xx(response, "delete memory projection");
            success.increment();
        } catch (RuntimeException error) {
            failure.increment();
            throw new MemoryIndexUnavailableException("Elasticsearch memory delete failed", error);
        }
    }

    private List<MemoryHit> doSearch(String tenantId, String userId, String agentType,
                                     float[] queryVector, int topK) {
        ensureIndex();
        List<Object> filters = List.of(
                Map.of("term", Map.of("tenantId", tenantId)), Map.of("term", Map.of("userId", userId)),
                Map.of("term", Map.of("agentType", agentType)), Map.of("term", Map.of("status", "ACTIVE")),
                Map.of("range", Map.of("expiresAt", Map.of("gt", "now"))),
                Map.of("range", Map.of("promptEligibleUntil", Map.of("gt", "now"))));
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "embedding"); knn.put("query_vector", queryVector);
        knn.put("k", Math.max(topK * 3, topK)); knn.put("num_candidates", Math.max(topK * 10, 50));
        knn.put("filter", Map.of("bool", Map.of("filter", filters)));
        HttpResponse<String> response = request("POST", "/" + index + "/_search",
                encode(Map.of("size", Math.max(topK * 3, topK), "knn", knn,
                        "_source", List.of("memoryId", "content", "importance", "confidence", "trustLevel",
                                "sourceType", "version", "occurredAt", "promptEligibleUntil"))));
        require2xx(response, "search episodic memory");
        List<MemoryHit> hits = new ArrayList<>();
        for (JsonNode hit : tree(response.body()).path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            Instant created = Instant.parse(source.path("occurredAt").asText());
            long ageDays = Math.max(0, ChronoUnit.DAYS.between(created, Instant.now()));
            double semantic = Math.max(0d, Math.min(1d, hit.path("_score").asDouble(0)));
            double importance = Math.max(0d, Math.min(1d, source.path("importance").asDouble(0)));
            MemoryTrustLevel trust = trust(source.path("trustLevel").asText("EXTERNAL_UNVERIFIED"));
            double score = semantic * .55 + importance * .20 + Math.exp(-ageDays / 90d) * .15
                    + trust.score() * .10;
            hits.add(new MemoryHit(source.path("memoryId").asText(""), source.path("content").asText(""),
                    score, source.path("confidence").asDouble(importance), trust,
                    source.path("sourceType").asText("UNKNOWN"), source.path("version").asLong(1), created,
                    Instant.parse(source.path("promptEligibleUntil").asText())));
        }
        return hits.stream().filter(hit -> !hit.content().isBlank() && hit.score() >= .35)
                .sorted(Comparator.comparingDouble(MemoryHit::score).reversed()).limit(topK).toList();
    }

    private synchronized void ensureIndex() {
        if (indexReady) return;
        HttpResponse<String> head = request("HEAD", "/" + index, null);
        if (head.statusCode() == 404) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("tenantId", Map.of("type", "keyword")); properties.put("userId", Map.of("type", "keyword"));
            properties.put("memoryId", Map.of("type", "keyword")); properties.put("agentType", Map.of("type", "keyword"));
            properties.put("contentHash", Map.of("type", "keyword")); properties.put("content", Map.of("type", "text"));
            properties.put("status", Map.of("type", "keyword")); properties.put("importance", Map.of("type", "float"));
            properties.put("confidence", Map.of("type", "float")); properties.put("trustLevel", Map.of("type", "keyword"));
            properties.put("sourceType", Map.of("type", "keyword")); properties.put("version", Map.of("type", "long"));
            properties.put("occurredAt", Map.of("type", "date")); properties.put("expiresAt", Map.of("type", "date"));
            properties.put("promptEligibleUntil", Map.of("type", "date"));
            properties.put("embedding", Map.of("type", "dense_vector", "dims", dimensions,
                    "index", true, "similarity", "cosine"));
            HttpResponse<String> create = request("PUT", "/" + index,
                    encode(Map.of("mappings", Map.of("properties", properties))));
            if (create.statusCode() == 400) {
                // 允许多个应用实例同时冷启动：若另一实例刚完成创建，二次检查即视为成功。
                require2xx(request("HEAD", "/" + index, null), "verify episodic memory index");
            } else require2xx(create, "create episodic memory index");
        } else require2xx(head, "check episodic memory index");
        indexReady = true;
    }

    private HttpResponse<String> request(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(timeout)
                    .header("Accept", "application/json");
            if (!authorization.isBlank()) builder.header("Authorization", authorization);
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) { throw new MemoryIndexUnavailableException("Elasticsearch request failed: " + path, error); }
    }

    private void require2xx(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new MemoryIndexUnavailableException(operation + " returned HTTP " + response.statusCode());
    }
    private String documentId(String tenantId, String userId, String hash) {
        return URLEncoder.encode(tenantId + "::" + userId + "::" + hash, StandardCharsets.UTF_8);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new MemoryIndexUnavailableException("Cannot encode memory request", error); }
    }
    private JsonNode tree(String value) {
        try { return json.readTree(value); }
        catch (Exception error) { throw new MemoryIndexUnavailableException("Cannot decode memory response", error); }
    }

    private void requireScope(String tenantId, String userId, String agentType) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()
                || agentType == null || agentType.isBlank())
            throw new IllegalArgumentException("Memory Elasticsearch 查询缺少 tenantId/userId/agentType");
    }

    private MemoryTrustLevel trust(String value) {
        try { return MemoryTrustLevel.valueOf(value); }
        catch (Exception ignored) { return MemoryTrustLevel.EXTERNAL_UNVERIFIED; }
    }

    public record MemoryHit(String memoryId, String content, double score, double confidence,
                            MemoryTrustLevel trustLevel, String sourceType, long version,
                            Instant occurredAt, Instant promptEligibleUntil) {}
    public static final class MemoryIndexUnavailableException extends RuntimeException {
        MemoryIndexUnavailableException(String message) { super(message); }
        MemoryIndexUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
