package com.hanaki.ecom.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.observability.ObservabilityModels.SpanWrite;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 标准 OTLP/HTTP JSON 批量导出器。
 *
 * <p>业务线程只把完成的 Span 放入有界队列；守护线程批量发送，失败有限重试一次。
 * Collector/Langfuse 不可用不会影响客服回答，本地数据库中的回放副本仍然完整。</p>
 */
@Component
public class OtlpHttpExporter {
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI endpoint;
    private final String serviceName;
    private final String serviceVersion;
    private final String environment;
    private final String promptVersion;
    private final boolean enabled;
    private final int batchSize;
    private final ArrayBlockingQueue<SpanWrite> queue;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("otlp-batch-exporter").factory());
    private final AtomicBoolean flushing = new AtomicBoolean();

    public OtlpHttpExporter(ObjectMapper json, MeterRegistry meters,
                            @Value("${agent.observability.otlp-endpoint:http://localhost:4318/v1/traces}") String endpoint,
                            @Value("${agent.observability.service-name:hanaki-ecom-agent}") String serviceName,
                            @Value("${agent.observability.app-version:1.0.0}") String serviceVersion,
                            @Value("${agent.observability.environment:local}") String environment,
                            @Value("${agent.observability.prompt-version:local}") String promptVersion,
                            @Value("${agent.observability.otlp-enabled:false}") boolean enabled,
                            @Value("${agent.observability.export-queue-capacity:2048}") int queueCapacity,
                            @Value("${agent.observability.export-batch-size:128}") int batchSize) {
        this.json = json;
        this.meters = meters;
        this.endpoint = URI.create(endpoint);
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.environment = environment;
        this.promptVersion = promptVersion;
        this.enabled = enabled;
        this.queue = new ArrayBlockingQueue<>(Math.max(128, queueCapacity));
        this.batchSize = Math.max(1, Math.min(512, batchSize));
        if (enabled) worker.scheduleWithFixedDelay(this::flushSafely, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void export(SpanWrite span) {
        if (!enabled) return;
        if (!queue.offer(span)) {
            Counter.builder("agent.telemetry.dropped.spans").tag("reason", "queue_full")
                    .register(meters).increment();
        } else if (queue.size() >= batchSize) {
            worker.execute(this::flushSafely);
        }
    }

    private void flushSafely() {
        if (!enabled || !flushing.compareAndSet(false, true)) return;
        try {
            List<SpanWrite> batch = new ArrayList<>(batchSize);
            queue.drainTo(batch, batchSize);
            if (!batch.isEmpty()) send(batch, 1);
        } catch (Exception error) {
            Counter.builder("agent.telemetry.export.failures").tag("reason", "serialization")
                    .register(meters).increment();
        } finally {
            flushing.set(false);
        }
    }

    private void send(List<SpanWrite> spans, int attemptsRemaining) {
        try {
            String body = json.writeValueAsString(payload(spans));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                boolean failed = error != null || response == null || response.statusCode() >= 300;
                if (!failed) {
                    Counter.builder("agent.telemetry.exported.spans").register(meters).increment(spans.size());
                    return;
                }
                if (attemptsRemaining > 0) {
                    worker.schedule(() -> send(spans, attemptsRemaining - 1), 500, TimeUnit.MILLISECONDS);
                } else {
                    Counter.builder("agent.telemetry.export.failures").tag("reason", "collector_unavailable")
                            .register(meters).increment();
                }
            });
        } catch (Exception error) {
            Counter.builder("agent.telemetry.export.failures").tag("reason", "request_build")
                    .register(meters).increment();
        }
    }

    private Map<String, Object> payload(List<SpanWrite> spans) {
        List<Map<String, Object>> values = spans.stream().map(this::otelSpan).toList();
        Map<String, Object> resource = Map.of("attributes", List.of(
                attribute("service.name", serviceName),
                attribute("service.version", serviceVersion),
                attribute("deployment.environment.name", environment),
                attribute("telemetry.sdk.language", "java")));
        Map<String, Object> scopeSpans = Map.of(
                "scope", Map.of("name", "hanaki-agent-telemetry", "version", "2.0"),
                "spans", values);
        return Map.of("resourceSpans", List.of(Map.of("resource", resource,
                "scopeSpans", List.of(scopeSpans))));
    }

    private Map<String, Object> otelSpan(SpanWrite span) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("traceId", normalizeTraceId(span.traceId()));
        value.put("spanId", normalizeSpanId(span.spanId()));
        if (span.parentSpanId() != null && !span.parentSpanId().isBlank())
            value.put("parentSpanId", normalizeSpanId(span.parentSpanId()));
        value.put("name", span.nodeName());
        value.put("kind", 1); // SPAN_KIND_INTERNAL
        value.put("startTimeUnixNano", nanos(span.startedAt()));
        value.put("endTimeUnixNano", nanos(span.finishedAt()));
        value.put("attributes", attributes(span));
        if (span.errorType() != null) {
            value.put("events", List.of(Map.of(
                    "timeUnixNano", nanos(span.finishedAt()), "name", "exception",
                    "attributes", List.of(attribute("exception.type", span.errorType()),
                            attribute("exception.message", text(span.errorMessage()))))));
        }
        value.put("status", Map.of("code", "ERROR".equals(span.status()) ? 2 : 1,
                "message", text(span.errorMessage())));
        return value;
    }

    private List<Map<String, Object>> attributes(SpanWrite span) {
        List<Map<String, Object>> values = new ArrayList<>();
        String observationType = observationType(span);
        values.add(attribute("langfuse.trace.name", "ecommerce-customer-service"));
        values.add(attribute("langfuse.user.id", span.userId()));
        values.add(attribute("langfuse.session.id", span.conversationId()));
        values.add(attribute("langfuse.version", promptVersion));
        values.add(attribute("langfuse.observation.type", observationType));
        values.add(attribute("langfuse.observation.input", text(span.inputJson())));
        values.add(attribute("langfuse.observation.output", text(span.outputJson())));
        values.add(attribute("langfuse.observation.metadata", text(span.metadataJson())));
        values.add(attribute("app.trace.id", span.traceId()));
        values.add(attribute("app.run.id", span.runId()));
        values.add(attribute("app.tenant.key", shortHash(span.tenantId())));
        values.add(attribute("agent.span.kind", span.spanKind()));
        values.add(attribute("agent.span.status", span.status()));
        if ("RUN".equals(span.spanKind())) {
            values.add(attribute("langfuse.trace.input", text(span.inputJson())));
            values.add(attribute("langfuse.trace.output", text(span.outputJson())));
        }
        if (span.modelName() != null) {
            values.add(attribute("langfuse.observation.model.name", span.modelName()));
            values.add(attribute("gen_ai.provider.name", "dashscope"));
            values.add(attribute("gen_ai.request.model", span.modelName()));
            values.add(attribute("gen_ai.operation.name", span.nodeName()));
            values.add(longAttribute("gen_ai.usage.input_tokens", span.promptTokens()));
            values.add(longAttribute("gen_ai.usage.output_tokens", span.completionTokens()));
            values.add(longAttribute("gen_ai.usage.total_tokens", span.totalTokens()));
            values.add(attribute("langfuse.observation.usage_details", usage(span)));
            values.add(attribute("langfuse.observation.metadata.cost_cny", span.costCny().toPlainString()));
        }
        return values;
    }

    private String observationType(SpanWrite span) {
        return switch (span.spanKind()) {
            case "RUN", "AGENT" -> "agent";
            case "MODEL" -> "generation";
            case "EMBEDDING" -> "embedding";
            case "RERANK" -> "reranker";
            case "RETRIEVAL" -> "retriever";
            case "TOOL" -> "tool";
            case "GUARDRAIL" -> "guardrail";
            case "CHAIN", "CHECKPOINT" -> "chain";
            default -> "span";
        };
    }

    private String usage(SpanWrite span) {
        try {
            return json.writeValueAsString(Map.of("input", span.promptTokens(),
                    "output", span.completionTokens(), "total", span.totalTokens()));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private Map<String, Object> attribute(String key, String value) {
        return Map.of("key", key, "value", Map.of("stringValue", text(value)));
    }

    private Map<String, Object> longAttribute(String key, long value) {
        return Map.of("key", key, "value", Map.of("intValue", String.valueOf(value)));
    }

    private String nanos(Instant value) {
        Instant safe = value == null ? Instant.now() : value;
        return String.valueOf(safe.getEpochSecond() * 1_000_000_000L + safe.getNano());
    }

    private String normalizeTraceId(String value) {
        String hex = value == null ? "" : value.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        String padded = "00000000000000000000000000000000" + hex;
        return padded.substring(padded.length() - 32);
    }

    private String normalizeSpanId(String value) {
        String hex = value == null ? "" : value.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        String padded = "0000000000000000" + hex;
        return padded.substring(padded.length() - 16);
    }

    private String shortHash(String value) {
        return Integer.toHexString(String.valueOf(value).hashCode());
    }

    private String text(String value) { return value == null ? "" : value; }

    @PreDestroy
    void close() {
        while (!queue.isEmpty()) flushSafely();
        worker.shutdown();
        try { worker.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
