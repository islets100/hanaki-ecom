package com.hanaki.ecom.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hanaki.ecom.domain.Domain.ChatRequest;
import com.hanaki.ecom.domain.Domain.ChatResponse;
import com.hanaki.ecom.domain.Domain.ExecutionStatus;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.RiskLevel;
import com.hanaki.ecom.domain.Domain.TenantAgentConfigSnapshot;
import com.hanaki.ecom.context.TrustedRequestContext;
import com.hanaki.ecom.memory.domain.MemoryScope;
import com.hanaki.ecom.memory.infrastructure.graph.GraphWorkingMemoryAdapter;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.support.SupportService;
import com.hanaki.ecom.observability.AgentTelemetryService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

/** 主 Graph 运行入口：创建 Run/Trace、装配短期记忆、保存检查点并保证消息幂等。 */
@Service
public class AgentOrchestrator {
    private final CompiledGraph graph;
    private final EcommerceStore store;
    private final SupportService support;
    private final AgentTelemetryService telemetry;
    private final MemoryContextService memory;
    private final RequestExecutionStore requests;
    private final TenantAgentConfigService tenantConfigs;
    private final ObjectMapper json;
    private final GraphWorkingMemoryAdapter workingMemory;
    private final String graphVersion;
    private final String promptVersion;
    private final int checkpointRetentionHours;
    /**
     * 消息幂等缓存与 Graph 节点/答案缓存严格分开：它的语义是“同一 messageId 只能提交一次”，
     * 不是“相似问题复用答案”。跨进程真相仍在 RequestExecutionStore，本 L1 只减少重复查库。
     * 使用权重而不是条数，防止少量很长回答挤占不可控内存。
     */
    private final Cache<String, ChatResponse> idempotency = Caffeine.newBuilder()
            .maximumWeight(16L * 1_024 * 1_024)
            .weigher((String key, ChatResponse response) -> Math.max(1,
                    key.length() * 2 + (response.answer() == null ? 0 : response.answer().length() * 2)))
            .expireAfterAccess(Duration.ofHours(24))
            .recordStats()
            .build();

    public AgentOrchestrator(CompiledGraph customerServiceGraph, EcommerceStore store,
                             SupportService support, ObjectMapper json,
                             AgentTelemetryService telemetry, MemoryContextService memory,
                             RequestExecutionStore requests, GraphWorkingMemoryAdapter workingMemory,
                             TenantAgentConfigService tenantConfigs,
                             @Value("${agent.checkpoint.graph-version:customer-service-graph-v3}") String graphVersion,
                             @Value("${agent.observability.prompt-version:local}") String promptVersion,
                             @Value("${agent.checkpoint.completed-retention-hours:24}") int checkpointRetentionHours) {
        this.graph = customerServiceGraph;
        this.store = store;
        this.support = support;
        this.json = json;
        this.telemetry = telemetry;
        this.memory = memory;
        this.requests = requests;
        this.workingMemory = workingMemory;
        this.tenantConfigs = tenantConfigs;
        this.graphVersion = graphVersion;
        this.promptVersion = promptVersion;
        this.checkpointRetentionHours = Math.max(2, checkpointRetentionHours);
    }

    public ChatResponse chat(ChatRequest request) {
        // 本机缓存键同样不保留 tenant/conversation/message 原文，避免身份标识出现在 heap dump。
        String key = CacheKeyBuilder.digest(String.join("|", request.tenantId(),
                request.conversationId(), request.messageId()));
        ChatResponse cached = idempotency.getIfPresent(key);
        if (cached != null) return cached;

        long start = System.nanoTime();
        RequestExecutionStore.ExecutionIdentity execution = requests.acquire(request, telemetry.newTraceId());
        if ("COMPLETED".equals(execution.status()) && execution.response() != null) {
            idempotency.put(key, execution.response());
            return execution.response();
        }
        if (!execution.acquired()) {
            ChatResponse response = requests.awaitCompletion(request);
            idempotency.put(key, response);
            return response;
        }
        requests.markRunning(request, execution);
        String run = execution.runId();
        String sub = execution.subRunId();
        String trace = execution.traceId();
        /*
         * conversationId 是产品层的一段对话；threadId 是 Checkpointer 的执行历史键；runId 是本条
         * 消息触发的一次运行。threadId 包含 runId，避免同一会话的并发消息恢复到彼此的节点快照；
         * 外层 RequestExecutionStore 再以 tenant/conversation/messageId 串行化同一消息。
         */
        String threadId = "main-" + telemetry.scopedKey(
                request.tenantId() + "|" + request.conversationId() + "|" + run);
        TenantAgentConfigSnapshot config = tenantConfigs.snapshot(request.tenantId());
        TrustedRequestContext trusted = new TrustedRequestContext(
                request.tenantId(), request.userId(), request.conversationId(), threadId, run, trace, "",
                Set.of("CUSTOMER_CHAT"), "SPRING_SECURITY_SESSION", config, RiskLevel.LOW);
        Map<String, Object> input = new HashMap<>();
        input.put("trustedContext", trusted);
        input.put("tenantId", request.tenantId());
        input.put("userId", request.userId());
        input.put("conversationId", request.conversationId());
        input.put("threadId", threadId);
        input.put("messageId", request.messageId());
        input.put("runId", run);
        input.put("subRunId", sub);
        input.put("traceId", trace);
        input.put("originalQuery", request.content());
        input.put("content", request.content());
        input.put("channelKind", "GENERAL");
        input.put("storeId", "");
        input.put("productId", "");
        input.put("productName", "");
        // 版本在 Run 启动时冻结；等待确认后恢复时仍沿用这些值，除非显式迁移并重新校验。
        input.put("tenantConfigVersion", config.tenantConfigVersion());
        input.put("promptVersion", config.promptVersion());
        input.put("policyVersion", config.policyVersion());
        input.put("knowledgeBaseVersion", config.knowledgeBaseVersion());
        input.put("toolSchemaVersion", config.toolSchemaVersion());
        input.put("routingConfigVersion", config.routingConfigVersion());
        input.put("topologyVersion", config.topologyVersion());
        /*
         * 第一阶段只给主 Graph 装载摘要与少量最近消息。此时意图尚未确定，禁止提前查询向量情景
         * 记忆或完整画像；否则不仅浪费 Embedding/Token，还可能把售前偏好泄露给投诉等无关节点。
         */
        MemoryScope mainMemoryScope = MemoryScope.conversation(request.tenantId(), request.userId(),
                request.conversationId(), run, sub, "INTENT_ROUTE");
        List<String> recentMessages = new ArrayList<>(memory.buildMainContext(
                mainMemoryScope, request.content(), trace).legacyLines());
        // 每一轮按服务端记录的会话类型装配上下文。店铺与官方会话使用不同数据表和
        // conversationId，因此两条客服链路不会互相串话。
        var storeSession = support.storeAiSession(request.tenantId(), request.userId(), request.conversationId());
        if (storeSession.isPresent()) {
            SupportService.StoreAiSession session = storeSession.get();
            input.put("channelKind", "STORE");
            input.put("storeId", session.storeId());
            input.put("productId", session.productId());
            input.put("productName", session.productName());
            recentMessages.addFirst("STORE_CONTEXT: 当前是店铺智能客服会话；店铺=" + session.storeName() +
                    "；storeId=" + session.storeId() + "；咨询商品=" + session.productName() +
                    "；productId=" + session.productId() +
                    "。订单查询默认锁定当前商品；只有用户明确询问整店订单时才扩大到当前店铺。");
        } else {
            support.officialAiContext(request.tenantId(), request.userId(), request.conversationId())
                    .ifPresent(context -> {
                        input.put("channelKind", "OFFICIAL");
                        recentMessages.addFirst(context);
                    });
        }
        input.put("recentMessages", List.copyOf(recentMessages));

        telemetry.startRun(trace, request.tenantId(), request.userId(), request.conversationId(), run, sub,
                Map.of("message", request.content(), "conversationId", request.conversationId()));
        OverAllState state;
        try {
            // threadId 已在可信上下文中冻结；Graph 调用和 State 中的审计副本必须使用同一个值。
            state = invoke(input, threadId);
        } catch (RuntimeException error) {
            telemetry.failRun(trace, error);
            requests.fail(request, execution, error);
            throw error;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        Intent intent = parse(Intent.class, state.value("intent", "UNKNOWN"), Intent.UNKNOWN);
        RiskLevel risk = parse(RiskLevel.class, state.value("riskLevel", "LOW"), RiskLevel.LOW);
        ExecutionStatus status = parse(ExecutionStatus.class,
                state.value("status", "COMPLETED"), ExecutionStatus.COMPLETED);
        ChatResponse response = new ChatResponse(run, sub, trace, intent,
                ((Number) state.value("confidence", 0d)).doubleValue(), risk,
                state.value("answer", ""), list(state, "evidence"), list(state, "toolResults"),
                status, blankToNull(state.<String>value("businessTaskId").orElse(null)),
                blankToNull(state.<String>value("confirmToken").orElse(null)),
                state.value("multiCandidate", false),
                blankToNull(state.<String>value("selectedCandidateId").orElse(null)), elapsedMs);

        boolean responseCommitted = false;
        try {
            store.saveMessage(request.tenantId(), request.userId(), request.conversationId(), run,
                    "USER", request.content());
            store.saveMessage(request.tenantId(), request.userId(), request.conversationId(), run,
                    "ASSISTANT", response.answer());
            if (response.businessTaskId() != null && !response.businessTaskId().isBlank()) {
                /*
                 * 任务关联采用同步短事务写入，确保“业务任务已创建”和“下轮能够定位任务”尽量具有
                 * 相同的提交边界。这里只保存 taskId/Agent/执行状态，不把 Graph State、订单或退款
                 * 详情复制到 Memory；以后恢复时，业务 Agent 必须按 taskId 回查权威业务表。
                 */
                MemoryScope taskScope = new MemoryScope(request.tenantId(), request.userId(),
                        request.conversationId(), run, sub, response.businessTaskId(), intent.name(),
                        "TASK_INDEX", "WEB");
                memory.recordConversationTask(taskScope, status.name());
            }
            try {
                telemetry.observeTrace(trace, "checkpoint.save", "CHECKPOINT",
                        Map.of("node", "END", "schemaVersion", "1"),
                        Map.of("checkpointAction", "save"), () -> {
                    try {
                        /*
                         * 只把小工作记忆契约写入审计 Checkpoint，不复制 RAG 正文、完整订单、画像或
                         * 全部消息。即使恢复该投影，业务节点也必须重新查询订单/任务的真实状态。
                         */
                        var checkpoint = workingMemory.project(state, "END");
                        String pendingType = checkpoint.pendingAction() == null
                                ? null : checkpoint.pendingAction().actionType();
                        store.saveCheckpoint(request.tenantId(), request.userId(), request.conversationId(), run,
                                "END", json.writeValueAsString(checkpoint), checkpoint.stateVersion(), graphVersion,
                                state.value("promptVersion", promptVersion),
                                state.value("tenantConfigVersion", "tenant-config-v1"),
                                state.value("policyVersion", "policy-v1"),
                                state.value("knowledgeBaseVersion", "knowledge-v1"),
                                state.value("toolSchemaVersion", "tool-schema-v1"),
                                state.value("routingConfigVersion", "routing-v1"),
                                state.value("topologyVersion", "standard-v1"),
                                pendingType, checkpoint.businessTaskId(),
                                java.time.Instant.now().plus(checkpointRetentionHours,
                                        java.time.temporal.ChronoUnit.HOURS));
                        return Map.of("saved", true);
                    } catch (Exception error) {
                        throw new IllegalStateException("Checkpoint 序列化或写入失败", error);
                    }
                });
            } catch (Exception ignored) {
                // 回答已经生成时，检查点失败留在 Trace 中，但不改变业务答复。
            }
            Map<String, Object> telemetryOutput = new HashMap<>();
            telemetryOutput.put("intent", intent.name());
            telemetryOutput.put("risk", risk.name());
            telemetryOutput.put("status", status.name());
            telemetryOutput.put("answer", response.answer());
            telemetryOutput.put("evidence", response.evidence());
            telemetryOutput.put("toolResults", response.toolResults());
            if (response.businessTaskId() != null) telemetryOutput.put("businessTaskId", response.businessTaskId());
            if (response.selectedCandidateId() != null) telemetryOutput.put("selectedCandidateId", response.selectedCandidateId());
            telemetry.finishRun(trace, status.name(), telemetryOutput);
            // 先提交跨进程可复用的响应，再调度非关键的异步 Memory 更新。
            requests.complete(request, execution, response);
            responseCommitted = true;
            idempotency.put(key, response);
            try {
                memory.update(request.tenantId(), request.userId(), request.conversationId(), run, trace,
                        request.content(), response.answer());
            } catch (RuntimeException asyncSchedulingFailure) {
                // Memory 是可补偿的非关键路径，不能在幂等响应已提交后把成功请求改成失败。
            }
            return response;
        } catch (RuntimeException error) {
            if (!responseCommitted) {
                try { requests.fail(request, execution, error); }
                catch (RuntimeException ignored) { /* 保留原始故障；租约到期后仍可恢复。 */ }
            }
            throw error;
        }
    }

    /** 将异步 Graph/候选 Future 包装的模型异常还原，API 才能返回明确的 503。 */
    private OverAllState invoke(Map<String, Object> input, String threadId) {
        try {
            return graph.invoke(input, RunnableConfig.builder().threadId(threadId).build()).orElseThrow();
        } catch (RuntimeException error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof ModelCallException modelError) throw modelError;
                current = current.getCause();
            }
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> list(OverAllState state, String key) {
        return state.value(key).map(value -> (List<String>) value).orElse(List.of());
    }

    private <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); }
        catch (Exception error) { return fallback; }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
