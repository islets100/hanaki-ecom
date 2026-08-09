package com.hanaki.ecom.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.hanaki.ecom.domain.Domain.ExecutionStatus;
import com.hanaki.ecom.domain.Domain.GuardResult;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.NodeResultType;
import com.hanaki.ecom.domain.Domain.RouteResult;
import com.hanaki.ecom.domain.Domain.RunStatus;
import com.hanaki.ecom.domain.Domain.SubGraphResult;
import com.hanaki.ecom.memory.infrastructure.graph.GraphCheckpointAdapter;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.support.SupportService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Spring AI Alibaba Graph 客服主拓扑。
 *
 * <p>该拓扑在启动时编译一次，适用于流程结构相同、仅 Prompt/知识库/规则/工具子集不同的租户。
 * 每次运行携带独立 {@code TrustedRequestContext} 和冻结的租户配置版本。如果将来某些租户需要完全
 * 不同的审批节点，应按 topologyVersion 编译多套图并由 Graph Registry 选择，而不是让 Prompt
 * 动态创造节点。</p>
 *
 * <p>主 Agent 只出现在 {@code intent_recognition}：它不能查询订单、退款或建工单。领域子 Graph
 * 不能互相调用，只能把 {@link NodeResultType} 返回给 {@code subgraph_result}；后者再通过固定条件边
 * 决定澄清、二次路由、等待确认、人工接管、阻断或输出。模型返回的任意字符串都不会直接成为节点名。</p>
 */
@Configuration
public class GraphConfiguration {
    private static final String REQUEST_INIT = "request_init";
    private static final String INPUT_RISK_CHECK = "input_risk_check";
    private static final String CONTEXT_LOAD = "context_load";
    private static final String INTENT_RECOGNITION = "intent_recognition";
    private static final String ROUTE_DECISION = "route_decision";
    private static final String PRE_SALES = "pre_sales_subgraph";
    private static final String IN_SALES = "in_sales_subgraph";
    private static final String AFTER_SALES = "after_sales_subgraph";
    private static final String COMPLAINT = "complaint_subgraph";
    private static final String SUBGRAPH_RESULT = "subgraph_result";
    private static final String CLARIFICATION = "clarification";
    private static final String USER_CONFIRMATION = "user_confirmation";
    private static final String HUMAN_HANDOFF = "human_handoff";
    private static final String BLOCKED = "blocked";
    private static final String OUTPUT_RISK_CHECK = "output_risk_check";
    private static final String FINAL_RESPONSE = "final_response";

    /** 条件边允许的键完全由服务端常量生成，不接受模型输出的节点名称。 */
    private static final Map<String, String> INITIAL_ROUTES = Map.of(
            Intent.PRE_SALE.name(), PRE_SALES,
            Intent.IN_SALE.name(), IN_SALES,
            Intent.AFTER_SALE.name(), AFTER_SALES,
            Intent.COMPLAINT.name(), COMPLAINT,
            "CLARIFY", CLARIFICATION,
            "HUMAN", HUMAN_HANDOFF,
            "BLOCKED", BLOCKED);

    /** 子 Graph 返回后的固定控制路径；允许一次受控重路由，但不允许 Agent 自由协商或循环调用。 */
    private static final Map<String, String> SUBGRAPH_ROUTES = Map.ofEntries(
            Map.entry("OUTPUT", OUTPUT_RISK_CHECK),
            Map.entry("CLARIFY", CLARIFICATION),
            Map.entry("CONFIRM", USER_CONFIRMATION),
            Map.entry("HUMAN", HUMAN_HANDOFF),
            Map.entry("BLOCKED", BLOCKED),
            Map.entry(Intent.PRE_SALE.name(), PRE_SALES),
            Map.entry(Intent.IN_SALE.name(), IN_SALES),
            Map.entry(Intent.AFTER_SALE.name(), AFTER_SALES),
            Map.entry(Intent.COMPLAINT.name(), COMPLAINT));

    /**
     * 编译客服主图。一次请求的标准路径是：可信输入校验 → 输入风控 → 最小上下文 → 意图识别
     * → 条件分发 → 单个领域子图 → 统一结果处理 → 输出风控。澄清、确认、人工和阻断都是主图的
     * 固定控制节点，不属于任何领域 Agent，因此业务子图无法绕过主图直接结束流程或创建人工工单。
     */
    @Bean
    CompiledGraph customerServiceGraph(GuardrailService guard, IntentRouter router,
                                       BusinessAgentGraphs agents, LogisticsAgentService logistics,
                                       SupportService support, AgentTelemetryService telemetry,
                                       GraphCheckpointAdapter checkpoints) throws GraphStateException {
        StateGraph graph = new StateGraph("ecommerce-customer-service", CustomerServiceGraphState.keyStrategies())
                .addNode(REQUEST_INIT, node_async(state -> telemetry.observeNode(state, REQUEST_INIT,
                        Map.of("stage", "TRUSTED_CONTEXT_VALIDATION"), () -> {
                            CustomerServiceGraphState.validateInitialState(state);
                            return patch(Map.of(
                                    "currentNode", REQUEST_INIT,
                                    "runStatus", RunStatus.INITIALIZED.name(),
                                    "rerouteCount", 0,
                                    "status", ExecutionStatus.COMPLETED.name()));
                        })))
                .addNode(INPUT_RISK_CHECK, node_async(state -> telemetry.observeNode(state, INPUT_RISK_CHECK,
                        Map.of("content", state.value("content", "")), () -> {
                            GuardResult result = guard.inspectInput(state.value("content", ""));
                            Map<String, Object> values = new HashMap<>();
                            values.put("currentNode", INPUT_RISK_CHECK);
                            values.put("runStatus", RunStatus.RUNNING.name());
                            values.put("riskLevel", result.level().name());
                            values.put("riskFlags", result.flags());
                            values.put("blocked", result.blocked());
                            values.put("forceHandoff", result.forceHandoff());
                            // safeMessage 只在阻断时写入；普通请求不能用空字符串覆盖后续业务答案。
                            if (result.blocked()) values.put("answer", result.safeMessage());
                            return patch(values);
                        })))
                .addNode(CONTEXT_LOAD, node_async(state -> telemetry.observeNode(state, CONTEXT_LOAD,
                        Map.of("stage", "MINIMAL_ROUTING_CONTEXT"), () -> {
                            TrustedContexts.from(state);
                            List<String> recent = stringList(state.value("recentMessages").orElse(List.of()));
                            // 主 Agent 最多看到最近 8 条轻量上下文；领域记忆在路由完成后由子图按需加载。
                            if (recent.size() > 8) recent = recent.subList(recent.size() - 8, recent.size());
                            return patch(Map.of("currentNode", CONTEXT_LOAD,
                                    "recentMessages", List.copyOf(recent)));
                        })))
                .addNode(INTENT_RECOGNITION, node_async(state -> telemetry.observeNode(state, INTENT_RECOGNITION,
                        Map.of("stage", "CLASSIFY_ONLY"), () -> {
                            if (state.value("blocked", false)) {
                                return patch(Map.of("currentNode", INTENT_RECOGNITION,
                                        "intent", Intent.UNKNOWN.name(),
                                        "secondaryIntent", Intent.UNKNOWN.name(),
                                        "modelSelfConfidence", 0d, "confidence", 0d,
                                        "confidenceBand", "LOW", "entities", Map.of(),
                                        "routingReason", "输入风控已阻断，不调用路由模型",
                                        "needsClarification", false));
                            }
                            String content = state.value("content", "");
                            List<String> recent = stringList(state.value("recentMessages").orElse(List.of()));
                            RouteResult result;
                            // 物流是明确的实时业务事实路径；保持确定性快车道，同时仍只能进入售中子图。
                            if (logistics.matches(content, recent)) {
                                result = new RouteResult(Intent.IN_SALE, 1d, List.of(Intent.IN_SALE),
                                        Map.of(), false, "命中服务端物流规则");
                            } else {
                                result = router.route(TrustedContexts.from(state), content, recent,
                                        state.value("forceHandoff", false));
                            }
                            Map<String, Object> values = new HashMap<>();
                            values.put("currentNode", INTENT_RECOGNITION);
                            values.put("intent", result.intent().name());
                            values.put("secondaryIntent", result.secondaryIntent().name());
                            values.put("modelSelfConfidence", result.modelSelfConfidence());
                            values.put("confidence", result.confidence());
                            values.put("confidenceBand", result.confidenceBand().name());
                            values.put("entities", result.entities());
                            values.put("routingReason", result.reason());
                            values.put("needsClarification", result.needClarification());
                            return patch(values);
                        })))
                .addNode(ROUTE_DECISION, node_async(state -> telemetry.observeNode(state, ROUTE_DECISION,
                        Map.of("intent", state.value("intent", "UNKNOWN")), () -> patch(Map.of(
                                "currentNode", ROUTE_DECISION,
                                "routeKey", initialRouteKey(state))))))
                .addNode(PRE_SALES, businessNode(telemetry, agents, Intent.PRE_SALE, PRE_SALES))
                .addNode(IN_SALES, businessNode(telemetry, agents, Intent.IN_SALE, IN_SALES))
                .addNode(AFTER_SALES, businessNode(telemetry, agents, Intent.AFTER_SALE, AFTER_SALES))
                .addNode(COMPLAINT, businessNode(telemetry, agents, Intent.COMPLAINT, COMPLAINT))
                .addNode(SUBGRAPH_RESULT, node_async(state -> telemetry.observeNode(state, SUBGRAPH_RESULT,
                        Map.of("nodeResultType", state.value("nodeResultType", "RETRYABLE_FAILURE")),
                        () -> patch(subGraphDecision(state)))))
                .addNode(CLARIFICATION, node_async(state -> telemetry.observeNode(state, CLARIFICATION,
                        Map.of("intent", state.value("intent", "UNKNOWN")), () -> {
                            String question = state.value("clarificationQuestion", "");
                            if (question.isBlank()) question = clarificationQuestion(state);
                            return patch(Map.of("currentNode", CLARIFICATION,
                                    "runStatus", RunStatus.WAITING.name(),
                                    "answer", question,
                                    "status", ExecutionStatus.NEED_CLARIFICATION.name()));
                        })))
                .addNode(USER_CONFIRMATION, node_async(state -> telemetry.observeNode(state, USER_CONFIRMATION,
                        Map.of("businessTaskId", state.value("businessTaskId", "")), () -> {
                            if (state.value("businessTaskId", "").isBlank()
                                    || state.value("confirmToken", "").isBlank()) {
                                throw new IllegalStateException("领域子图要求用户确认，但未返回任务或确认令牌");
                            }
                            return patch(Map.of("currentNode", USER_CONFIRMATION,
                                    "runStatus", RunStatus.WAITING.name(),
                                    "status", ExecutionStatus.WAITING_CONFIRMATION.name()));
                        })))
                .addNode(HUMAN_HANDOFF, node_async(state -> telemetry.observeNode(state, HUMAN_HANDOFF,
                        Map.of("reason", state.value("failureReason", "")), () -> {
                            var trusted = TrustedContexts.from(state);
                            List<String> riskFlags = stringList(state.value("riskFlags").orElse(List.of()));
                            boolean complaint = Intent.COMPLAINT.name().equals(state.value("intent", "UNKNOWN"))
                                    || riskFlags.contains("COMPLAINT") || riskFlags.contains("SEVERE_THREAT");
                            String caseId = support.createHandoff(trusted.tenantId(), trusted.userId(),
                                    trusted.conversationId(), state.value("content", ""), complaint);
                            String answer = complaint
                                    ? "已记录你的投诉，并转交商城官方人工客服继续处理。"
                                    : "已为你转接人工客服，客服会基于当前会话摘要继续处理。";
                            return patch(Map.of("currentNode", HUMAN_HANDOFF,
                                    "runStatus", RunStatus.WAITING.name(), "answer", answer,
                                    "status", ExecutionStatus.HANDOFF.name(), "businessTaskId", caseId));
                        })))
                .addNode(BLOCKED, node_async(state -> telemetry.observeNode(state, BLOCKED,
                        Map.of("blocked", true), () -> patch(Map.of(
                                "currentNode", BLOCKED, "runStatus", RunStatus.BLOCKED.name(),
                                "status", ExecutionStatus.BLOCKED.name())))))
                .addNode(OUTPUT_RISK_CHECK, node_async(state -> telemetry.observeNode(state, OUTPUT_RISK_CHECK,
                        Map.of("answer", state.value("answer", "")), () -> patch(Map.of(
                                "currentNode", OUTPUT_RISK_CHECK,
                                "answer", guard.sanitizeOutput(state.value("answer", "")))))))
                .addNode(FINAL_RESPONSE, node_async(state -> telemetry.observeNode(state, FINAL_RESPONSE,
                        Map.of("status", state.value("status", "COMPLETED")), () -> patch(Map.of(
                                "currentNode", FINAL_RESPONSE,
                                "runStatus", finalRunStatus(state).name())))))
                .addEdge(StateGraph.START, REQUEST_INIT)
                .addEdge(REQUEST_INIT, INPUT_RISK_CHECK)
                .addEdge(INPUT_RISK_CHECK, CONTEXT_LOAD)
                .addEdge(CONTEXT_LOAD, INTENT_RECOGNITION)
                .addEdge(INTENT_RECOGNITION, ROUTE_DECISION)
                .addConditionalEdges(ROUTE_DECISION,
                        edge_async(state -> state.value("routeKey", "CLARIFY")), INITIAL_ROUTES);

        for (String domainNode : List.of(PRE_SALES, IN_SALES, AFTER_SALES, COMPLAINT))
            graph.addEdge(domainNode, SUBGRAPH_RESULT);
        graph.addConditionalEdges(SUBGRAPH_RESULT,
                edge_async(state -> state.value("routeKey", "HUMAN")), SUBGRAPH_ROUTES);
        for (String outputNode : List.of(CLARIFICATION, USER_CONFIRMATION, HUMAN_HANDOFF, BLOCKED))
            graph.addEdge(outputNode, OUTPUT_RISK_CHECK);
        graph.addEdge(OUTPUT_RISK_CHECK, FINAL_RESPONSE);
        graph.addEdge(FINAL_RESPONSE, StateGraph.END);
        return graph.compile(checkpoints.compileConfig("main"));
    }

    private static com.alibaba.cloud.ai.graph.action.AsyncNodeAction businessNode(
            AgentTelemetryService telemetry, BusinessAgentGraphs agents, Intent intent, String nodeName) {
        return node_async(state -> telemetry.observeNode(state, nodeName,
                Map.of("intent", intent.name()), () -> subGraphPatch(agents.execute(intent, state), nodeName)));
    }

    /** 把统一子图协议投影回主 Graph，不接受子图提供的任意 nextNode。 */
    private static Map<String, Object> subGraphPatch(SubGraphResult result, String nodeName) {
        Map<String, Object> values = new HashMap<>();
        values.put("currentNode", nodeName);
        values.put("nodeResultType", result.resultType().name());
        values.put("rerouteIntent", result.rerouteIntent().name());
        values.put("clarificationQuestion", result.clarificationQuestion());
        values.put("failureReason", result.failureReason());
        values.put("answer", result.responseDraft());
        values.put("evidence", result.evidence());
        values.put("toolResults", result.toolResults());
        values.put("status", result.executionStatus().name());
        values.put("businessTaskId", blank(result.businessTaskId()));
        values.put("confirmToken", blank(result.confirmToken()));
        values.put("multiCandidate", result.multiCandidate());
        values.put("selectedCandidateId", blank(result.selectedCandidateId()));
        return patch(values);
    }

    /**
     * 把意图识别结果收敛为 INITIAL_ROUTES 中的服务端路由键。IntentRouter 已经完成置信度校准，
     * 所以这里不再重复比较浮点阈值，只读取 needsClarification；阻断和强制人工始终拥有更高优先级。
     */
    private static String initialRouteKey(com.alibaba.cloud.ai.graph.OverAllState state) {
        if (state.value("blocked", false)) return "BLOCKED";
        Intent intent = parseIntent(state.value("intent", "UNKNOWN"));
        if (intent == Intent.HUMAN_SERVICE || state.value("forceHandoff", false)) return "HUMAN";
        if (state.value("needsClarification", true) || intent == Intent.UNKNOWN) return "CLARIFY";
        return switch (intent) {
            case PRE_SALE, IN_SALE, AFTER_SALE, COMPLAINT -> intent.name();
            case HUMAN_SERVICE -> "HUMAN";
            case UNKNOWN -> "CLARIFY";
        };
    }

    /**
     * 解释领域子图的强类型返回值。所有分支只能落到 SUBGRAPH_ROUTES 白名单；允许的二次领域路由
     * 最多一次，失败或非法目标统一降级到人工，避免业务 Agent 之间形成不可控循环。
     */
    private static Map<String, Object> subGraphDecision(com.alibaba.cloud.ai.graph.OverAllState state) {
        NodeResultType result = parseResultType(state.value("nodeResultType", "RETRYABLE_FAILURE"));
        Map<String, Object> values = new HashMap<>();
        values.put("currentNode", SUBGRAPH_RESULT);
        switch (result) {
            case SUCCESS -> values.put("routeKey", "OUTPUT");
            case NEED_MORE_INFORMATION -> values.put("routeKey", "CLARIFY");
            case NEED_USER_CONFIRMATION -> values.put("routeKey", "CONFIRM");
            case NEED_HUMAN, RETRYABLE_FAILURE -> values.put("routeKey", "HUMAN");
            case BLOCKED -> values.put("routeKey", "BLOCKED");
            case NEED_REROUTE -> {
                int count = ((Number) state.value("rerouteCount", 0)).intValue();
                Intent target = parseIntent(state.value("rerouteIntent", "UNKNOWN"));
                boolean registeredDomain = target == Intent.PRE_SALE || target == Intent.IN_SALE
                        || target == Intent.AFTER_SALE || target == Intent.COMPLAINT;
                if (!registeredDomain || count >= 1) {
                    values.put("routeKey", "HUMAN");
                    values.put("failureReason", "子图重路由目标无效或超过一次受控重路由上限");
                } else {
                    values.put("routeKey", target.name());
                    values.put("intent", target.name());
                    values.put("rerouteCount", count + 1);
                }
            }
        }
        return values;
    }

    private static String clarificationQuestion(com.alibaba.cloud.ai.graph.OverAllState state) {
        Intent intent = parseIntent(state.value("intent", "UNKNOWN"));
        return switch (intent) {
            case PRE_SALE -> "请补充想咨询的商品名称、型号，或你最关注的参数。";
            case IN_SALE -> "请说明要查询支付、发货还是物流；若有订单号也可以一并提供。";
            case AFTER_SALE -> "售后意图已经确认，请补充本人订单号和退换货原因，以便继续校验资格。";
            case COMPLAINT -> "请说明投诉涉及的订单、店铺和希望解决的问题。";
            case HUMAN_SERVICE -> "如需人工客服，请回复“确认转人工”。";
            case UNKNOWN -> "请补充你要咨询的是商品、订单物流、售后，还是投诉问题。";
        };
    }

    private static RunStatus finalRunStatus(com.alibaba.cloud.ai.graph.OverAllState state) {
        ExecutionStatus status;
        try { status = ExecutionStatus.valueOf(state.value("status", "COMPLETED")); }
        catch (Exception ignored) { status = ExecutionStatus.FAILED; }
        return switch (status) {
            case COMPLETED -> RunStatus.COMPLETED;
            case NEED_CLARIFICATION, WAITING_CONFIRMATION, WAITING_STAFF_APPROVAL, HANDOFF -> RunStatus.WAITING;
            case BLOCKED -> RunStatus.BLOCKED;
            case FAILED -> RunStatus.FAILED;
        };
    }

    private static Intent parseIntent(String value) {
        try { return Intent.valueOf(value); }
        catch (Exception ignored) { return Intent.UNKNOWN; }
    }

    private static NodeResultType parseResultType(String value) {
        try { return NodeResultType.valueOf(value); }
        catch (Exception ignored) { return NodeResultType.RETRYABLE_FAILURE; }
    }

    private static Map<String, Object> patch(Map<String, Object> values) {
        return CustomerServiceGraphState.checkedPatch(values);
    }

    private static String blank(String value) { return value == null ? "" : value; }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
