package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.hanaki.ecom.observability.AgentTelemetryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 调用阿里云百炼 qwen3-rerank 的生产级重排适配器，并在失败时让上层显式降级。 */
@Component
public final class DashScopeRerankClient {
    private final boolean enabled;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maximumDocuments;
    private final int maximumDocumentChars;
    private final int maximumQueryChars;
    private final HttpClient http;
    private final ObjectMapper json;
    private final AgentTelemetryService telemetry;
    private final Counter success;
    private final Counter failure;

    public DashScopeRerankClient(
            @Value("${agent.rag.rerank.enabled:false}") boolean enabled,
            @Value("${agent.rag.rerank.endpoint:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}") URI endpoint,
            @Value("${AI_DASHSCOPE_API_KEY:}") String apiKey,
            @Value("${agent.rag.rerank.model:qwen3-rerank}") String model,
            @Value("${agent.rag.rerank.timeout-seconds:8}") long timeoutSeconds,
            @Value("${agent.rag.rerank.maximum-documents:30}") int maximumDocuments,
            @Value("${agent.rag.rerank.maximum-document-chars:6000}") int maximumDocumentChars,
            @Value("${agent.rag.rerank.maximum-query-chars:2000}") int maximumQueryChars,
            ObjectMapper json, AgentTelemetryService telemetry, MeterRegistry meters) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.maximumDocuments = Math.max(1, Math.min(100, maximumDocuments));
        this.maximumDocumentChars = Math.max(200, maximumDocumentChars);
        this.maximumQueryChars = Math.max(100, maximumQueryChars);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.json = json;
        this.telemetry = telemetry;
        this.success = meters.counter("agent.rag.rerank.requests", "result", "success", "model", model);
        this.failure = meters.counter("agent.rag.rerank.requests", "result", "failure", "model", model);
    }

    public boolean enabled() { return enabled && !apiKey.isBlank(); }

    public RerankResponse rerank(String query, List<String> documents, int topN) {
        if (!enabled()) throw new RerankUnavailableException("DashScope rerank is disabled or API key is missing");
        if (documents.isEmpty()) return new RerankResponse(List.of(), 0, "empty");
        /*
         * Rerank 是相关性模型，不应收到无限长正文。控制字符和 HTML/脚本标签先移除，再按配置截断；
         * 候选只取 RRF 前 maximumDocuments 条，因此返回 index 仍与调用方输入前缀严格一一对应。
         * 这既限制外部调用成本，也减少知识正文中的提示注入文本干扰重排指令的机会。
         */
        String safeQuery = sanitize(query, maximumQueryChars);
        List<String> safeDocuments = documents.stream().limit(maximumDocuments)
                .map(document -> sanitize(document, maximumDocumentChars)).toList();
        try {
            return telemetry.observeCurrent("rag.rerank.remote", "RERANK",
                    Map.of("queryLength", safeQuery.length(), "documentCount", safeDocuments.size()),
                    Map.of("provider", "dashscope", "model", model),
                    () -> executeWithOneRetry(safeQuery, safeDocuments, topN));
        } catch (RuntimeException error) {
            failure.increment();
            throw error;
        }
    }

    private RerankResponse executeWithOneRetry(String query, List<String> documents, int topN) {
        RuntimeException first;
        try { return execute(query, documents, topN); }
        catch (RuntimeException error) { first = error; }
        try { return execute(query, documents, topN); }
        catch (RuntimeException second) {
            second.addSuppressed(first);
            throw second;
        }
    }

    private RerankResponse execute(String query, List<String> documents, int topN) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("query", query == null ? "" : query);
            body.put("documents", documents);
            body.put("top_n", Math.max(1, Math.min(topN, documents.size())));
            body.put("instruct", "根据电商客服问题判断文档能否直接、准确支撑回答，优先权威政策与明确商品信息。");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new RerankUnavailableException("DashScope rerank HTTP " + response.statusCode());
            JsonNode root = json.readTree(response.body());
            if (root.hasNonNull("code"))
                throw new RerankUnavailableException("DashScope rerank rejected request: " + root.path("code").asText());
            JsonNode results = root.path("results"); // qwen3-rerank: results is at the response root
            if (!results.isArray()) results = root.path("output").path("results");
            if (!results.isArray()) throw new RerankUnavailableException("DashScope rerank response has no results array");
            List<RankedDocument> ranked = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(Double.NaN);
                if (index < 0 || index >= documents.size() || !Double.isFinite(score)) continue;
                if (!seen.add(index))
                    throw new RerankUnavailableException("DashScope rerank response contains duplicate index " + index);
                ranked.add(new RankedDocument(index, Math.max(0d, Math.min(1d, score))));
            }
            if (ranked.isEmpty()) throw new RerankUnavailableException("DashScope rerank returned no valid document index");
            int tokens = Math.max(0, root.path("usage").path("total_tokens").asInt(0));
            success.increment();
            return new RerankResponse(List.copyOf(ranked), tokens, root.path("id").asText(""));
        } catch (RerankUnavailableException error) { throw error; }
        catch (Exception error) { throw new RerankUnavailableException("DashScope rerank call failed", error); }
    }

    public record RankedDocument(int index, double score) {}
    public record RerankResponse(List<RankedDocument> documents, int totalTokens, String requestId) {}

    public static final class RerankUnavailableException extends RuntimeException {
        RerankUnavailableException(String message) { super(message); }
        RerankUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    private String sanitize(String value, int maximumChars) {
        if (value == null) return "";
        String sanitized = value
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return sanitized.length() <= maximumChars ? sanitized : sanitized.substring(0, maximumChars);
    }
}
