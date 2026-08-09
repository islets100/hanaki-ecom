package com.hanaki.ecom.agent;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hanaki.ecom.context.TrustedRequestContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客服主 Graph 的状态字段契约。
 *
 * <p>Spring AI Alibaba Graph 的 {@link OverAllState} 本质上是按 key 管理的状态容器。如果字段名散落
 * 在每个节点中，后续开发者很容易拼错名称、意外覆盖身份，或把完整订单对象放进 Checkpoint。
 * 本类集中定义字段、写入策略和边界校验，使“哪些字段不可修改、哪些字段是路由结果、哪些字段只
 * 能作为轻量业务引用”在代码层可见。</p>
 *
 * <h2>字段写入原则</h2>
 * <ul>
 *   <li>{@link #IMMUTABLE_KEYS}：仅由接入层/编排器在启动 Run 时写入，任何 Graph 节点都不得返回同名 key。</li>
 *   <li>{@link #ROUTING_KEYS}：只能由语义风控、意图识别和路由决策节点覆盖。</li>
 *   <li>{@link #RESULT_KEYS}：只能由领域子图、人工接管、输出风控和最终响应节点覆盖。</li>
 * </ul>
 *
 * <p>Graph State 只保存 orderId、businessTaskId、工具结果引用等必要摘要，不保存完整订单 JSON、
 * 身份凭证、手机号、地址或下游原始响应。真实订单/退款状态始终由带 tenantId + userId 约束的
 * 业务服务重新查询。</p>
 */
public final class CustomerServiceGraphState {
    private CustomerServiceGraphState() {}

    public static final String TRUSTED_CONTEXT = "trustedContext";
    public static final String TENANT_ID = "tenantId";
    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String THREAD_ID = "threadId";
    public static final String MESSAGE_ID = "messageId";
    public static final String RUN_ID = "runId";
    public static final String SUB_RUN_ID = "subRunId";
    public static final String TRACE_ID = "traceId";
    public static final String ORIGINAL_QUERY = "originalQuery";
    public static final String CONTENT = "content";
    public static final String RECENT_MESSAGES = "recentMessages";
    public static final String CHANNEL_KIND = "channelKind";
    public static final String STORE_ID = "storeId";
    public static final String PRODUCT_ID = "productId";
    public static final String PRODUCT_NAME = "productName";

    public static final String TENANT_CONFIG_VERSION = "tenantConfigVersion";
    public static final String PROMPT_VERSION = "promptVersion";
    public static final String POLICY_VERSION = "policyVersion";
    public static final String KNOWLEDGE_BASE_VERSION = "knowledgeBaseVersion";
    public static final String TOOL_SCHEMA_VERSION = "toolSchemaVersion";
    public static final String ROUTING_CONFIG_VERSION = "routingConfigVersion";
    public static final String TOPOLOGY_VERSION = "topologyVersion";

    public static final String CURRENT_NODE = "currentNode";
    public static final String RUN_STATUS = "runStatus";
    public static final String RISK_LEVEL = "riskLevel";
    public static final String RISK_FLAGS = "riskFlags";
    public static final String BLOCKED = "blocked";
    public static final String FORCE_HANDOFF = "forceHandoff";
    public static final String INTENT = "intent";
    public static final String SECONDARY_INTENT = "secondaryIntent";
    public static final String MODEL_SELF_CONFIDENCE = "modelSelfConfidence";
    public static final String CONFIDENCE = "confidence";
    public static final String CONFIDENCE_BAND = "confidenceBand";
    public static final String ENTITIES = "entities";
    public static final String ROUTING_REASON = "routingReason";
    public static final String NEEDS_CLARIFICATION = "needsClarification";
    public static final String ROUTE_KEY = "routeKey";
    public static final String REROUTE_COUNT = "rerouteCount";

    public static final String NODE_RESULT_TYPE = "nodeResultType";
    public static final String REROUTE_INTENT = "rerouteIntent";
    public static final String CLARIFICATION_QUESTION = "clarificationQuestion";
    public static final String FAILURE_REASON = "failureReason";
    public static final String ANSWER = "answer";
    public static final String EVIDENCE = "evidence";
    public static final String TOOL_RESULTS = "toolResults";
    public static final String STATUS = "status";
    public static final String BUSINESS_TASK_ID = "businessTaskId";
    public static final String CONFIRM_TOKEN = "confirmToken";
    public static final String MULTI_CANDIDATE = "multiCandidate";
    public static final String SELECTED_CANDIDATE_ID = "selectedCandidateId";

    public static final Set<String> IMMUTABLE_KEYS = Set.of(
            TRUSTED_CONTEXT, TENANT_ID, USER_ID, CONVERSATION_ID, THREAD_ID, MESSAGE_ID,
            RUN_ID, SUB_RUN_ID, TRACE_ID, ORIGINAL_QUERY, CONTENT,
            CHANNEL_KIND, STORE_ID, PRODUCT_ID, PRODUCT_NAME,
            TENANT_CONFIG_VERSION, PROMPT_VERSION, POLICY_VERSION, KNOWLEDGE_BASE_VERSION,
            TOOL_SCHEMA_VERSION, ROUTING_CONFIG_VERSION, TOPOLOGY_VERSION);

    public static final Set<String> ROUTING_KEYS = Set.of(
            CURRENT_NODE, RUN_STATUS, RISK_LEVEL, RISK_FLAGS, BLOCKED, FORCE_HANDOFF,
            INTENT, SECONDARY_INTENT, MODEL_SELF_CONFIDENCE, CONFIDENCE, CONFIDENCE_BAND,
            ENTITIES, ROUTING_REASON, NEEDS_CLARIFICATION, ROUTE_KEY, REROUTE_COUNT);

    public static final Set<String> RESULT_KEYS = Set.of(
            NODE_RESULT_TYPE, REROUTE_INTENT, CLARIFICATION_QUESTION, FAILURE_REASON,
            ANSWER, EVIDENCE, TOOL_RESULTS, STATUS, BUSINESS_TASK_ID, CONFIRM_TOKEN,
            MULTI_CANDIDATE, SELECTED_CANDIDATE_ID);

    /**
     * 为 Graph 注册完整 key 集合。状态都采用 ReplaceStrategy，因为每个节点返回的是该字段的完整、
     * 已校验新值；列表追加会在 Checkpoint 恢复重放时重复累积风险标记或工具引用。
     */
    public static KeyStrategyFactory keyStrategies() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : allKeys()) strategies.put(key, new ReplaceStrategy());
            return strategies;
        };
    }

    /** 请求初始化节点使用的 fail-closed 校验。 */
    public static TrustedRequestContext validateInitialState(OverAllState state) {
        TrustedRequestContext trusted = TrustedContexts.from(state);
        requireText(state, MESSAGE_ID);
        requireText(state, CONTENT);
        requireText(state, TENANT_CONFIG_VERSION);
        requireText(state, PROMPT_VERSION);
        requireText(state, POLICY_VERSION);
        requireText(state, KNOWLEDGE_BASE_VERSION);
        requireText(state, TOOL_SCHEMA_VERSION);
        requireText(state, ROUTING_CONFIG_VERSION);
        requireText(state, TOPOLOGY_VERSION);
        return trusted;
    }

    /**
     * 校验节点补丁没有覆盖身份或输入。所有主 Graph 节点在返回 Map 前都通过该方法，形成一条统一的
     * 代码级约束；这样即使以后新增节点，也必须显式面对不可变字段列表。
     */
    public static Map<String, Object> checkedPatch(Map<String, Object> patch) {
        if (patch == null) return Map.of();
        Set<String> illegal = new java.util.HashSet<>(patch.keySet());
        illegal.retainAll(IMMUTABLE_KEYS);
        if (!illegal.isEmpty())
            throw new IllegalArgumentException("Graph 节点试图覆盖不可变状态字段：" + illegal);
        return Map.copyOf(patch);
    }

    private static List<String> allKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(IMMUTABLE_KEYS);
        keys.add(RECENT_MESSAGES);
        keys.addAll(ROUTING_KEYS);
        keys.addAll(RESULT_KEYS);
        return List.copyOf(keys);
    }

    private static String requireText(OverAllState state, String key) {
        String value = state.value(key, "");
        if (value.isBlank()) throw new IllegalStateException("Graph State 缺少必填字段：" + key);
        return value;
    }
}
