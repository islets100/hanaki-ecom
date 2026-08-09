package com.hanaki.ecom.observability;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.agent.ModelCallException;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.observability.ObservabilityModels.SpanWrite;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Agent 全链路可观测核心。
 *
 * <p>一份业务 Span 同时进入三个去向：本地只读回放库、低基数 Micrometer/Prometheus
 * 指标以及标准 OTLP/HTTP 导出器。这里使用 OpenTelemetry Context API 传播父子关系，
 * 并显式包装并行候选任务，避免 CompletableFuture 切换线程后 Trace 断链。</p>
 *
 * <p>Trace 只保存经过脱敏的执行现场，不承担退款审计、幂等或 Graph 恢复职责。</p>
 */
@Service
public class AgentTelemetryService {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|authorization|password|cookie|set-cookie|access[_-]?token|refresh[_-]?token|token)([\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\\"',}\\s]+)"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/-]+=*");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<![A-Za-z0-9])\\d{13,19}(?![A-Za-z0-9])");
    private static final Pattern EMAIL = Pattern.compile("(?i)([a-z0-9._%+-]{1,3})[a-z0-9._%+-]*(@[a-z0-9.-]+)");

    private final ObservabilityStore store;
    private final OtlpHttpExporter exporter;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final String serviceName;
    private final String environment;
    private final String appVersion;
    private final String modelName;
    private final String modelProvider;
    private final String promptVersion;
    private final String knowledgeVersion;
    private final String skillVersion;
    private final String priceVersion;
    private final BigDecimal inputRate;
    private final BigDecimal outputRate;
    private final int payloadLimit;
    private final Map<String, RootRun> roots = new ConcurrentHashMap<>();
    /**
     * 根 Span 完成后短暂保留只读上下文，使异步 Memory/Outbox 仍能作为同一 Trace 的子 Span 写入。
     * 这里只保留已脱敏的根元数据，并按时间淘汰。
     */
    private final Map<String, CompletedRoot> completedRoots = new ConcurrentHashMap<>();
    private final Map<String, ActiveSpan> openSpans = new ConcurrentHashMap<>();
    private final ThreadLocal<ActiveSpan> active = new ThreadLocal<>();

    public AgentTelemetryService(ObservabilityStore store, OtlpHttpExporter exporter,
                                 ObjectMapper json, MeterRegistry meters,
                                 @Value("${agent.observability.service-name:hanaki-ecom-agent}") String serviceName,
                                 @Value("${agent.observability.environment:local}") String environment,
                                 @Value("${agent.observability.app-version:1.0.0}") String appVersion,
                                 @Value("${agent.observability.model-name:qwen-plus}") String modelName,
                                 @Value("${agent.observability.model-provider:dashscope}") String modelProvider,
                                 @Value("${agent.observability.prompt-version:local}") String promptVersion,
                                 @Value("${agent.observability.knowledge-version:seed-v1}") String knowledgeVersion,
                                 @Value("${agent.observability.skill-version:2026-08-01}") String skillVersion,
                                 @Value("${agent.observability.price-version:2026-08-01}") String priceVersion,
                                 @Value("${agent.observability.input-cost-per-million-cny:0.8}") BigDecimal inputRate,
                                 @Value("${agent.observability.output-cost-per-million-cny:2.0}") BigDecimal outputRate,
                                 @Value("${agent.observability.payload-limit:4000}") int payloadLimit) {
        this.store = store;
        this.exporter = exporter;
        this.json = json;
        this.meters = meters;
        this.serviceName = serviceName;
        this.environment = environment;
        this.appVersion = appVersion;
        this.modelName = modelName;
        this.modelProvider = modelProvider;
        this.promptVersion = promptVersion;
        this.knowledgeVersion = knowledgeVersion;
        this.skillVersion = skillVersion;
        this.priceVersion = priceVersion;
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.payloadLimit = Math.max(500, payloadLimit);
    }

    /** 自动探针存在时沿用入口 HTTP Trace；否则创建新的 W3C 兼容 32 位 TraceId。 */
    public String newTraceId() {
        SpanContext current = Span.current().getSpanContext();
        return current.isValid() ? current.getTraceId() : randomTraceId();
    }

    public void startRun(String traceId, String tenantId, String userId, String conversationId,
                         String runId, String subRunId, Object input) {
        SpanContext upstream = Span.current().getSpanContext();
        String normalizedTrace = normalizeTraceId(traceId);
        String rootSpanId = randomSpanId();
        SpanContext rootSpanContext = SpanContext.create(normalizedTrace, rootSpanId,
                TraceFlags.getSampled(), TraceState.getDefault());
        Context parent = upstream.isValid() && upstream.getTraceId().equals(normalizedTrace)
                ? Context.current() : Context.root();
        String upstreamParent = upstream.isValid() && upstream.getTraceId().equals(normalizedTrace)
                ? upstream.getSpanId() : null;
        Context rootContext = parent.with(Span.wrap(rootSpanContext));
        roots.put(traceId, new RootRun(traceId, rootSpanId, upstreamParent, tenantId, userId,
                conversationId, runId, subRunId, safe(input), Instant.now(), rootContext));
    }

    public void finishRun(String traceId, String status, Object output) {
        RootRun root = roots.remove(traceId);
        if (root == null) return;
        rememberCompleted(root);
        Instant finished = Instant.now();
        Map<String, Object> metadata = commonMetadata(root, "agent.run", "RUN", status);
        if (output instanceof Map<?, ?> values) {
            copyIfPresent(values, metadata, "businessTaskId");
            copyIfPresent(values, metadata, "intent");
            copyIfPresent(values, metadata, "risk");
        }
        persist(root, root.rootSpanId(), root.upstreamParentSpanId(), "agent.run", "RUN", status,
                root.input(), safe(output), safe(metadata), null, null, null,
                0, 0, 0, BigDecimal.ZERO, root.started(), finished);
        Duration duration = Duration.between(root.started(), finished);
        Timer.builder("agent.run.duration").tag("environment", low(environment)).tag("result", low(status))
                .register(meters).record(duration);
        Counter.builder("agent.run.total").tag("environment", low(environment)).tag("result", low(status))
                .register(meters).increment();
        if ("HANDOFF".equals(status)) {
            Counter.builder("agent.human.handoff.total").tag("reason", "intent")
                    .register(meters).increment();
        }
        clearOpen(traceId);
    }

    public void failRun(String traceId, Throwable error) {
        RootRun root = roots.remove(traceId);
        if (root == null) return;
        rememberCompleted(root);
        Instant finished = Instant.now();
        ErrorInfo info = classify(error);
        Map<String, Object> metadata = commonMetadata(root, "agent.run", "RUN", "ERROR");
        metadata.putAll(info.asMap());
        persist(root, root.rootSpanId(), root.upstreamParentSpanId(), "agent.run", "RUN", "ERROR",
                root.input(), "", safe(metadata), error.getClass().getName(), message(error), null,
                0, 0, 0, BigDecimal.ZERO, root.started(), finished);
        Timer.builder("agent.run.duration").tag("environment", low(environment)).tag("result", "error")
                .register(meters).record(Duration.between(root.started(), finished));
        Counter.builder("agent.run.total").tag("environment", low(environment)).tag("result", "error")
                .register(meters).increment();
        errorMetric("agent.run", info);
        clearOpen(traceId);
    }

    public <T> T observeNode(OverAllState state, String nodeName, Object input, Supplier<T> work) {
        String kind = nodeName.contains("guard") || "blocked".equals(nodeName) ? "GUARDRAIL"
                : nodeName.matches("pre_sale|in_sale|after_sale|complaint|clarify") ? "AGENT" : "CHAIN";
        return observe(state.value("traceId", ""), nodeName, kind, input, Map.of(), work);
    }

    public <T> T observeRetrieval(OverAllState state, Object input, Supplier<T> work) {
        return observe(state.value("traceId", ""), "rag.hybrid_search", "RETRIEVAL", input,
                Map.of("knowledgeVersion", knowledgeVersion, "strategy", "BM25+VECTOR+RRF"), work);
    }

    /** 记录模型原始参数和网关注入身份、权限后的生效参数，两者都先脱敏。 */
    public <T> T observeTool(String traceId, String toolName, Object requestedArgs,
                             Object effectiveArgs, Supplier<T> work) {
        return observe(traceId, "tool." + toolName, "TOOL",
                Map.of("requestedArgs", requestedArgs, "effectiveArgs", effectiveArgs),
                Map.of("toolName", toolName, "toolVersion", "commerce-tools-v1", "permissionResult", "checked"),
                work);
    }

    public <T> T observeTool(String traceId, String toolName, Object arguments, Supplier<T> work) {
        return observeTool(traceId, toolName, arguments, Map.of("scope", "server-bound"), work);
    }

    public <T> T observeCandidate(String traceId, int variant, Object input, Supplier<T> work) {
        return observe(traceId, "candidate.run." + variant, "AGENT", input,
                Map.of("candidateVariant", variant), work);
    }

    /** 为 RAG 内部分阶段、Checkpoint 等不直接持有 Graph State 的代码创建子 Span。 */
    public <T> T observeCurrent(String name, String kind, Object input,
                                Map<String, Object> metadata, Supplier<T> work) {
        ActiveSpan parent = active.get();
        return parent == null ? work.get()
                : observe(parent.root().traceId(), name, kind, input, metadata, work);
    }

    /** 在 Graph 返回后仍可把 Checkpoint、Memory 更新等步骤挂到本轮根 Span。 */
    public <T> T observeTrace(String traceId, String name, String kind, Object input,
                              Map<String, Object> metadata, Supplier<T> work) {
        return observe(traceId, name, kind, input, metadata, work);
    }

    /**
     * 延迟任务可能在根 Run 完成很久以后才执行。此入口从本地审计库恢复根 Span 标识，
     * 让知识审核、Outbox 和重试仍出现在原 Trace 回放中；恢复失败时才退化为普通执行。
     */
    public <T> T observeLinkedTrace(String traceId, String name, String kind, Object input,
                                    Map<String, Object> metadata, Supplier<T> work) {
        if (traceId == null || traceId.isBlank()) return work.get();
        if (rootFor(traceId) == null) {
            store.context(traceId).ifPresent(context -> {
                SpanContext spanContext = SpanContext.create(normalizeTraceId(context.traceId()),
                        context.rootSpanId(), TraceFlags.getSampled(), TraceState.getDefault());
                RootRun restored = new RootRun(context.traceId(), context.rootSpanId(), null,
                        context.tenantId(), context.userId(), context.conversationId(), context.runId(),
                        "detached", "", context.startedAt() == null ? Instant.now() : context.startedAt(),
                        Context.root().with(Span.wrap(spanContext)));
                completedRoots.put(traceId, new CompletedRoot(restored, Instant.now().plus(Duration.ofMinutes(15))));
            });
        }
        return observe(traceId, name, kind, input, metadata, work);
    }

    /** 捕获当前 OpenTelemetry Context 与业务父 Span，显式传播到线程池。 */
    public <T> Supplier<T> propagate(Supplier<T> work) {
        ActiveSpan captured = active.get();
        Context capturedContext = Context.current();
        return () -> {
            ActiveSpan previous = active.get();
            try (Scope ignored = capturedContext.makeCurrent()) {
                if (captured == null) active.remove(); else active.set(captured);
                return work.get();
            } finally {
                if (previous == null) active.remove(); else active.set(previous);
            }
        };
    }

    /** 模型 Token 取自供应商响应元数据；价格版本随 Span 固化，历史成本不会被新价格重算。 */
    public <T> T observeModel(String stage, Object input, Supplier<ModelExchange<T>> work) {
        ActiveSpan parent = active.get();
        if (parent == null) return work.get().value();
        Instant started = Instant.now();
        ActiveSpan current = child(parent.root(), parent, "model." + stage);
        ActiveSpan previous = active.get();
        openSpans.put(openKey(parent.root().traceId(), "model." + stage), current);
        try (Scope ignored = current.context().makeCurrent()) {
            active.set(current);
            ModelExchange<T> exchange = work.get();
            Instant finished = Instant.now();
            BigDecimal cost = cost(exchange.promptTokens(), exchange.completionTokens());
            Map<String, Object> metadata = commonMetadata(parent.root(), "model." + stage, "MODEL", "OK");
            metadata.put("modelProvider", modelProvider);
            metadata.put("promptVersion", promptVersion);
            metadata.put("priceVersion", priceVersion);
            metadata.put("usageSource", exchange.usageSource());
            metadata.put("cachedTokens", exchange.cachedTokens());
            metadata.put("finishReason", exchange.finishReason());
            persist(parent.root(), current.spanId(), current.parentSpanId(), "model." + stage, "MODEL", "OK",
                    safe(input), safe(exchange.value()), safe(metadata), null, null, modelName,
                    exchange.promptTokens(), exchange.completionTokens(), exchange.totalTokens(), cost,
                    started, finished);
            recordModelMetrics(stage, exchange, cost, Duration.between(started, finished), "ok");
            return exchange.value();
        } catch (RuntimeException error) {
            failChild(parent.root(), current, "model." + stage, "MODEL", input, Map.of(), started, error);
            recordModelFailure(stage, Duration.between(started, Instant.now()), error);
            throw error;
        } finally {
            openSpans.remove(openKey(parent.root().traceId(), "model." + stage), current);
            if (previous == null) active.remove(); else active.set(previous);
        }
    }

    public String currentTraceId() {
        ActiveSpan value = active.get();
        return value == null ? "" : value.root().traceId();
    }

    public String scopedKey(String value) { return value == null ? "unknown" : sha256(value).substring(0, 12); }

    public record ModelExchange<T>(T value, int promptTokens, int completionTokens, int totalTokens,
                                   int cachedTokens, String finishReason, String usageSource) {
        public ModelExchange(T value, int promptTokens, int completionTokens, int totalTokens) {
            this(value, promptTokens, completionTokens, totalTokens, 0, "unknown", "model-response");
        }
    }

    private <T> T observe(String traceId, String name, String kind, Object input,
                          Map<String, Object> nodeMetadata, Supplier<T> work) {
        RootRun root = rootFor(traceId);
        if (root == null) return work.get();
        ActiveSpan previous = active.get();
        ActiveSpan parent = previous != null && previous.root().traceId().equals(traceId)
                ? previous : inferredParent(root, name);
        ActiveSpan current = child(root, parent, name);
        Instant started = Instant.now();
        active.set(current);
        openSpans.put(openKey(traceId, name), current);
        try (Scope ignored = current.context().makeCurrent()) {
            T output = work.get();
            Instant finished = Instant.now();
            Map<String, Object> metadata = commonMetadata(root, name, kind, "OK");
            metadata.putAll(nodeMetadata);
            metadata.putAll(resultMetadata(kind, output));
            persist(root, current.spanId(), current.parentSpanId(), name, kind, "OK", safe(input), safe(output),
                    safe(metadata), null, null, null, 0, 0, 0, BigDecimal.ZERO, started, finished);
            recordNodeMetrics(name, kind, "ok", output, Duration.between(started, finished));
            return output;
        } catch (RuntimeException error) {
            failChild(root, current, name, kind, input, nodeMetadata, started, error);
            throw error;
        } finally {
            openSpans.remove(openKey(traceId, name), current);
            if (previous == null) active.remove(); else active.set(previous);
        }
    }

    private ActiveSpan child(RootRun root, ActiveSpan parent, String name) {
        String parentId = parent == null ? root.rootSpanId() : parent.spanId();
        Context parentContext = parent == null ? root.rootContext() : parent.context();
        String spanId = randomSpanId();
        SpanContext spanContext = SpanContext.create(normalizeTraceId(root.traceId()), spanId,
                TraceFlags.getSampled(), TraceState.getDefault());
        return new ActiveSpan(root, spanId, parentId, name, parentContext.with(Span.wrap(spanContext)));
    }

    private RootRun rootFor(String traceId) {
        RootRun live = roots.get(traceId);
        if (live != null) return live;
        CompletedRoot completed = completedRoots.get(traceId);
        if (completed == null) return null;
        if (completed.expiresAt().isBefore(Instant.now())) {
            completedRoots.remove(traceId, completed);
            return null;
        }
        return completed.root();
    }

    private void rememberCompleted(RootRun root) {
        Instant now = Instant.now();
        completedRoots.put(root.traceId(), new CompletedRoot(root, now.plus(Duration.ofMinutes(15))));
        if (completedRoots.size() > 2_048) {
            completedRoots.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        }
    }

    /** Graph 子图可能切换内部线程；按固定节点命名空间恢复仍在执行的父业务 Agent。 */
    private ActiveSpan inferredParent(RootRun root, String name) {
        String candidate = name;
        while (candidate.contains(".")) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            ActiveSpan found = openSpans.get(openKey(root.traceId(), candidate));
            if (found != null) return found;
        }
        return null;
    }

    private void failChild(RootRun root, ActiveSpan current, String name, String kind,
                           Object input, Map<String, Object> nodeMetadata,
                           Instant started, RuntimeException error) {
        ErrorInfo info = classify(error);
        Map<String, Object> metadata = commonMetadata(root, name, kind, "ERROR");
        metadata.putAll(nodeMetadata);
        metadata.putAll(info.asMap());
        Instant finished = Instant.now();
        persist(root, current.spanId(), current.parentSpanId(), name, kind, "ERROR", safe(input), "",
                safe(metadata), error.getClass().getName(), message(error), null,
                0, 0, 0, BigDecimal.ZERO, started, finished);
        recordNodeMetrics(name, kind, "error", null, Duration.between(started, finished));
        errorMetric(name, info);
        if ("TOOL".equals(kind)) {
            Counter.builder("agent.tool.error.total").tag("tool", low(toolName(name)))
                    .tag("errorCategory", low(info.category())).register(meters).increment();
        }
    }

    private void recordNodeMetrics(String name, String kind, String status, Object output, Duration elapsed) {
        Timer.builder("agent.node.duration").tag("node", low(name)).tag("kind", low(kind))
                .tag("result", status).register(meters).record(elapsed);
        if ("TOOL".equals(kind)) {
            String tool = low(toolName(name));
            Timer.builder("agent.tool.duration").tag("tool", tool).tag("result", status)
                    .register(meters).record(elapsed);
            Counter.builder("agent.tool.calls").tag("tool", tool).tag("result", status)
                    .register(meters).increment();
        }
        if ("RETRIEVAL".equals(kind)) {
            int count = output instanceof Collection<?> values ? values.size() : 0;
            Timer.builder("agent.rag.stage.duration").tag("stage", low(name)).tag("result", status)
                    .register(meters).record(elapsed);
            meters.summary("agent.retrieval.documents", "stage", low(name)).record(count);
            if (count == 0) Counter.builder("agent.rag.empty.total").tag("stage", low(name))
                    .register(meters).increment();
        }
        if ("GUARDRAIL".equals(kind) && output instanceof Map<?, ?> map
                && Boolean.TRUE.equals(map.get("blocked"))) {
            Counter.builder("agent.guardrail.block.total").tag("stage", low(name))
                    .register(meters).increment();
        }
    }

    private void recordModelMetrics(String stage, ModelExchange<?> exchange, BigDecimal cost,
                                    Duration elapsed, String result) {
        Timer.builder("agent.model.duration").tag("model", low(modelName)).tag("stage", low(stage))
                .tag("result", result).register(meters).record(elapsed);
        Counter.builder("agent.model.tokens").tag("model", low(modelName)).tag("direction", "input")
                .register(meters).increment(exchange.promptTokens());
        Counter.builder("agent.model.tokens").tag("model", low(modelName)).tag("direction", "output")
                .register(meters).increment(exchange.completionTokens());
        if (exchange.cachedTokens() > 0) Counter.builder("agent.model.tokens")
                .tag("model", low(modelName)).tag("direction", "cached")
                .register(meters).increment(exchange.cachedTokens());
        Counter.builder("agent.model.cost.cny").tag("model", low(modelName)).tag("priceVersion", low(priceVersion))
                .register(meters).increment(cost.doubleValue());
    }

    private void recordModelFailure(String stage, Duration elapsed, Throwable error) {
        ErrorInfo info = classify(error);
        Timer.builder("agent.model.duration").tag("model", low(modelName)).tag("stage", low(stage))
                .tag("result", "error").register(meters).record(elapsed);
        Counter.builder("agent.model.error.total").tag("model", low(modelName))
                .tag("errorCategory", low(info.category())).register(meters).increment();
    }

    private void errorMetric(String node, ErrorInfo info) {
        Counter.builder("agent.errors").tag("node", low(node))
                .tag("errorCategory", low(info.category())).register(meters).increment();
    }

    private BigDecimal cost(int promptTokens, int completionTokens) {
        return inputRate.multiply(BigDecimal.valueOf(promptTokens))
                .add(outputRate.multiply(BigDecimal.valueOf(completionTokens)))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private void persist(RootRun root, String spanId, String parentSpanId, String name, String kind, String status,
                         String input, String output, String metadata, String errorType, String errorMessage,
                         String model, int promptTokens, int completionTokens, int totalTokens, BigDecimal cost,
                         Instant started, Instant finished) {
        SpanWrite span = new SpanWrite(UUID.randomUUID().toString(), root.traceId(), spanId, parentSpanId,
                root.tenantId(), root.userId(), root.conversationId(), root.runId(), name, kind, status,
                Math.max(0, Duration.between(started, finished).toMillis()), status, input, output, metadata,
                errorType, errorMessage, model, promptTokens, completionTokens, totalTokens, cost, started, finished);
        store.save(span);
        exporter.export(span);
    }

    private Map<String, Object> commonMetadata(RootRun root, String name, String kind, String status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("environment", environment);
        metadata.put("service", serviceName);
        metadata.put("appVersion", appVersion);
        metadata.put("telemetrySchemaVersion", "agent-otel-v2");
        metadata.put("tenantKey", scopedKey(root.tenantId()));
        metadata.put("conversationId", root.conversationId());
        metadata.put("runId", root.runId());
        metadata.put("subRunId", root.subRunId());
        metadata.put("agentType", agentType(name));
        metadata.put("nodeName", name);
        metadata.put("nodeKind", kind);
        metadata.put("nodeStatus", status);
        metadata.put("promptVersion", promptVersion);
        metadata.put("knowledgeVersion", knowledgeVersion);
        metadata.put("skillVersion", skillVersion);
        return metadata;
    }

    private Map<String, Object> resultMetadata(String kind, Object output) {
        Map<String, Object> values = new LinkedHashMap<>();
        if ("RETRIEVAL".equals(kind) && output instanceof Collection<?> collection) {
            values.put("candidateCount", collection.size());
            values.put("cacheHit", false);
        }
        if ("GUARDRAIL".equals(kind) && output instanceof Map<?, ?> map) {
            Object risk = map.containsKey("riskLevel") ? map.get("riskLevel") : "UNKNOWN";
            values.put("riskLevel", String.valueOf(risk));
            values.put("blocked", Boolean.TRUE.equals(map.get("blocked")));
        }
        return values;
    }

    private Object snapshot(Object value) {
        if (value instanceof KnowledgeDoc doc) {
            return Map.of("documentRef", doc.id(), "title", doc.title(), "version", doc.version(),
                    "score", doc.score(), "contentHash", sha256(doc.content()));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            map.forEach((key, item) -> cleaned.put(String.valueOf(key), snapshot(item)));
            return cleaned;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> cleaned = new ArrayList<>(collection.size());
            collection.forEach(item -> cleaned.add(snapshot(item)));
            return cleaned;
        }
        return value;
    }

    private String safe(Object value) {
        if (value == null) return "";
        String text;
        try {
            Object snapshot = snapshot(value);
            text = snapshot instanceof String string ? string : json.writeValueAsString(snapshot);
        } catch (Exception error) {
            text = String.valueOf(value);
        }
        text = SECRET.matcher(text).replaceAll("$1$2***");
        text = BEARER.matcher(text).replaceAll("Bearer ***");
        text = MOBILE.matcher(text).replaceAll("1**********");
        text = BANK_CARD.matcher(text).replaceAll("************");
        text = EMAIL.matcher(text).replaceAll("$1***$2");
        return text.length() <= payloadLimit ? text : text.substring(0, payloadLimit) + "…";
    }

    private ErrorInfo classify(Throwable error) {
        Throwable root = unwrap(error);
        String name = root.getClass().getSimpleName().toLowerCase();
        String message = String.valueOf(root.getMessage()).toLowerCase();
        if (root instanceof SecurityException || message.contains("权限") || message.contains("归属"))
            return new ErrorInfo("PERMISSION_DENIED", false, "blocked", "tool-gateway");
        if (root instanceof TimeoutException || name.contains("timeout") || message.contains("超时"))
            return new ErrorInfo("TIMEOUT", true, "retry-or-handoff", serviceFor(error));
        if (message.contains("限流") || message.contains("429") || message.contains("rate limit"))
            return new ErrorInfo("RATE_LIMITED", true, "backoff", modelProvider);
        if (message.contains("内容过滤") || message.contains("content filter"))
            return new ErrorInfo("CONTENT_FILTERED", false, "safe-response", modelProvider);
        if (message.contains("token") && (message.contains("超") || message.contains("limit")))
            return new ErrorInfo("TOKEN_LIMIT", false, "truncate-context", modelProvider);
        if (root instanceof ModelCallException)
            return new ErrorInfo("MODEL_CALL_FAILED", true, "retry-or-degrade", modelProvider);
        if (root instanceof IllegalArgumentException)
            return new ErrorInfo("INVALID_ARGUMENT", false, "reject", serviceFor(error));
        return new ErrorInfo("UNEXPECTED", false, "handoff", serviceFor(error));
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current.getClass().getSimpleName().contains("Execution"))
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private String serviceFor(Throwable error) {
        String name = error.getClass().getName();
        if (name.contains("jdbc") || name.contains("sql")) return "database";
        if (name.contains("redis")) return "redis";
        return serviceName;
    }

    private String message(Throwable error) {
        String value = error.getMessage();
        return safe(value == null || value.isBlank() ? error.getClass().getSimpleName() : value);
    }

    private String agentType(String name) {
        if (name == null) return "unknown";
        int separator = name.indexOf('.');
        String prefix = separator < 0 ? name : name.substring(0, separator);
        return switch (prefix) {
            case "pre_sale", "in_sale", "after_sale", "complaint", "clarify" -> prefix;
            default -> "main";
        };
    }

    private String toolName(String spanName) {
        return spanName != null && spanName.startsWith("tool.") ? spanName.substring(5) : spanName;
    }

    private String openKey(String traceId, String name) { return traceId + "|" + name; }
    private void clearOpen(String traceId) { openSpans.keySet().removeIf(key -> key.startsWith(traceId + "|")); }

    private void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private String low(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (Exception ignored) {
            return "hash-unavailable";
        }
    }

    private String normalizeTraceId(String value) {
        String hex = value == null ? "" : value.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        String padded = "00000000000000000000000000000000" + hex;
        return padded.substring(padded.length() - 32);
    }

    private String randomTraceId() { return UUID.randomUUID().toString().replace("-", ""); }
    private String randomSpanId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    private record RootRun(String traceId, String rootSpanId, String upstreamParentSpanId,
                           String tenantId, String userId, String conversationId, String runId,
                           String subRunId, String input, Instant started, Context rootContext) {}
    private record CompletedRoot(RootRun root, Instant expiresAt) {}
    private record ActiveSpan(RootRun root, String spanId, String parentSpanId,
                              String name, Context context) {}
    private record ErrorInfo(String category, boolean retryable, String fallbackAction,
                             String rootCauseService) {
        Map<String, Object> asMap() {
            return Map.of("errorCategory", category, "retryable", retryable,
                    "attempt", 1, "fallbackAction", fallbackAction,
                    "rootCauseService", rootCauseService);
        }
    }
}
