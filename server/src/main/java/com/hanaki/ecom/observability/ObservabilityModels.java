package com.hanaki.ecom.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 可观测 API 使用的只读数据结构。敏感原始对象在写入前已经由 AgentTelemetryService 脱敏。 */
public final class ObservabilityModels {
    private ObservabilityModels() {}

    public record SpanWrite(String id, String traceId, String spanId, String parentSpanId,
                            String tenantId, String userId, String conversationId, String runId,
                            String nodeName, String spanKind, String status, long elapsedMs,
                            String result, String inputJson, String outputJson, String metadataJson,
                            String errorType, String errorMessage, String modelName,
                            int promptTokens, int completionTokens, int totalTokens,
                            BigDecimal costCny, Instant startedAt, Instant finishedAt) {}

    public record TraceSpanView(String spanId, String parentSpanId, String nodeName, String kind,
                                String status, long elapsedMs, String input, String output,
                                String metadata, String errorType, String errorMessage,
                                String modelName, int promptTokens, int completionTokens,
                                int totalTokens, BigDecimal costCny,
                                Instant startedAt, Instant finishedAt) {}

    public record TraceSummary(String traceId, String runId, String conversationId, String status,
                               long elapsedMs, int spanCount, int promptTokens,
                               int completionTokens, int totalTokens, BigDecimal costCny,
                               Instant startedAt) {}

    public record TraceReplay(String traceId, TraceSummary summary, List<TraceSpanView> spans) {}

    public record ObservabilityOverview(int traces, int successfulTraces, int failedTraces,
                                        long averageLatencyMs, int modelCalls, int retrievals,
                                        int toolCalls, long promptTokens, long completionTokens,
                                        long totalTokens, BigDecimal totalCostCny) {}
}
