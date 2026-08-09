package com.hanaki.ecom.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hanaki.ecom.domain.Domain.AgentResult;
import com.hanaki.ecom.domain.Domain.ExecutionStatus;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.NodeResultType;
import com.hanaki.ecom.domain.Domain.SubGraphResult;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.context.ProgressiveSkillRegistry;
import com.hanaki.ecom.memory.domain.MemoryScope;
import com.hanaki.ecom.memory.infrastructure.graph.GraphCheckpointAdapter;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 四个真正独立编译的领域 SubGraph。
 *
 * <p>RAG 不是主 Graph 中的一次黑盒方法调用，而是业务 Agent 内部具有明确输入/输出协议的子链路：
 * Query Rewrite → Trusted Query Router → 多路并行召回 → RRF → Rerank → 证据校验。拆开的节点会
 * 分别产生 trace 和 checkpoint；排障时可以直接判断是改写、某个召回分支还是证据冲突导致降级。</p>
 */
@Component
public class BusinessAgentGraphs {
    /**
     * 这里只注册业务领域。UNKNOWN 由主 Graph 的 clarificationNode 处理，HUMAN_SERVICE 由通用
     * humanHandoffNode 处理；两者都没有业务工具，因此绝不能为了代码复用而编译成“业务 Agent”。
     */
    private static final Set<Intent> DOMAIN_INTENTS = EnumSet.of(
            Intent.PRE_SALE, Intent.IN_SALE, Intent.AFTER_SALE, Intent.COMPLAINT);
    /*
     * 主 Graph 进入领域子图时采用显式白名单投影。店铺/商品范围属于服务端确认的业务身份，
     * 必须与 tenantId、userId 一起进入子图；否则子图会退化成“当前用户全部订单”查询。
     * keys() 与 project() 共用同一份列表，防止以后只改其中一处再次丢失范围。
     */
    static final List<String> SUBGRAPH_INPUT_KEYS = List.of(
            "trustedContext", "tenantId", "userId", "conversationId", "threadId",
            "messageId", "runId", "subRunId", "traceId", "originalQuery", "content",
            "channelKind", "storeId", "productId", "productName",
            "tenantConfigVersion", "promptVersion", "policyVersion", "knowledgeBaseVersion",
            "toolSchemaVersion", "routingConfigVersion", "topologyVersion",
            "recentMessages", "riskLevel", "forceHandoff", "confidence", "entities",
            "routingReason", "needsClarification");
    private final Map<Intent, CompiledGraph> graphs = new EnumMap<>(Intent.class);
    private final MemoryContextService memory;

    public BusinessAgentGraphs(AgentExecutionService execution, AgentTelemetryService telemetry,
                               ProgressiveSkillRegistry skills, MemoryContextService memory,
                               GraphCheckpointAdapter checkpoints) throws GraphStateException {
        this.memory = memory;
        for (Intent intent : DOMAIN_INTENTS)
            graphs.put(intent, compile(intent, execution, telemetry, skills, checkpoints));
    }

    /**
     * 应用层把每次进入子 Graph 记为独立 SubRun。SubRun 是本项目的审计概念，不假装是框架固定术语；
     * 它使用稳定 threadId 支持恢复，并只接收 project() 明确列出的状态字段。
     */
    public SubGraphResult execute(Intent intent, OverAllState parent) {
        if (!DOMAIN_INTENTS.contains(intent))
            throw new IllegalArgumentException("非业务意图不能进入领域 SubGraph：" + intent);
        CompiledGraph graph = graphs.get(intent);
        if (graph == null) throw new IllegalArgumentException("未注册领域 SubGraph：" + intent);
        Map<String, Object> input = project(intent, parent);
        String subRun = parent.value("subRunId", "sub-unknown") + "-" + intent.name();
        OverAllState state = graph.invoke(input, RunnableConfig.builder().threadId(subRun).build())
                .orElseThrow(() -> new IllegalStateException(intent + " SubGraph 没有返回状态"));
        return fromState(state);
    }

    /**
     * 为指定领域单独编译一张同构子图。四张图复用节点实现，但各自拥有独立名称、Checkpoint 命名空间
     * 和 Intent；每次主图只会调用其中一张，因此售前、售中、售后、投诉不会在同一运行里共享工具对象。
     */
    private CompiledGraph compile(Intent intent, AgentExecutionService execution,
                                  AgentTelemetryService telemetry, ProgressiveSkillRegistry skills,
                                  GraphCheckpointAdapter checkpoints) throws GraphStateException {
        StateGraph graph = new StateGraph(intent.name().toLowerCase() + "-agent-subgraph", keys())
                .addNode("query_rewrite", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".rag.query_rewrite",
                        Map.of("intent", intent.name(), "stage", "QUERY_REWRITE"),
                        () -> execution.rewriteRagQuery(state, intent))))
                .addNode("query_router", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".rag.query_router",
                        Map.of("intent", intent.name(), "stage", "TRUSTED_QUERY_ROUTER"),
                        () -> execution.routeRagQuery(state, intent))))
                .addNode("parallel_recall", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".rag.parallel_recall",
                        Map.of("intent", intent.name(), "stage", "MULTI_RECALL"),
                        () -> execution.recallRag(state))))
                .addNode("rrf_fusion", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".rag.rrf_fusion",
                        Map.of("intent", intent.name(), "stage", "RRF"),
                        () -> execution.fuseRag(state))))
                .addNode("rerank", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".rag.rerank",
                        Map.of("intent", intent.name(), "stage", "RERANK"),
                        () -> execution.rerankRag(state))))
                .addNode("evidence_validate", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".rag.evidence_validate",
                        Map.of("intent", intent.name(), "stage", "EVIDENCE_VALIDATE"),
                        () -> execution.validateRagEvidence(state))))
                .addNode("select_skill", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".select_skill",
                        Map.of("intent", intent.name(), "disclosurePhase", "CARD"), () -> {
                            /*
                             * Skill 第一阶段使用低成本确定性选择，不向改写节点披露 Schema。这里仅把
                             * skillKey 引用写入子图状态；下一次回答模型调用前，ContextAssembler 会按
                             * 节点策略、Agent 归属和风险再次授权，之后才允许绑定完整工具 Schema。
                             */
                            String selected = skills.selectCard(intent, state.value("content", ""));
                            return Map.of("selectedSkillKey", selected,
                                    "skillDisclosurePhase", selected.isBlank() ? "NONE" : "SCHEMA");
                        })))
                .addNode("model_agent", node_async(state -> telemetry.observeNode(state,
                        intent.name().toLowerCase() + ".model_agent",
                        Map.of("intent", intent.name()), () -> result(execution.generate(state, intent)))))
                .addEdge(StateGraph.START, "query_rewrite")
                .addEdge("query_rewrite", "query_router")
                .addEdge("query_router", "parallel_recall")
                .addEdge("parallel_recall", "rrf_fusion")
                .addEdge("rrf_fusion", "rerank")
                .addEdge("rerank", "evidence_validate")
                .addEdge("evidence_validate", "select_skill")
                .addEdge("select_skill", "model_agent")
                .addEdge("model_agent", StateGraph.END);
        return graph.compile(checkpoints.compileConfig("business/" + intent.name().toLowerCase()));
    }

    /** 子图状态同样使用覆盖策略，保证恢复执行时不会重复追加召回结果、工具结果或回答。 */
    private KeyStrategyFactory keys() {
        return () -> {
            Map<String, KeyStrategy> map = new HashMap<>();
            for (String key : SUBGRAPH_INPUT_KEYS) map.put(key, new ReplaceStrategy());
            for (String key : List.of("intent", "rewrittenQuery", "retrievedChunkRefs",
                    "ragRewrite", "ragSkipKnowledge", "ragClarificationRequired", "ragPlan", "ragRoutingReason",
                    "ragRecall", "ragFusion", "ragRerank", "ragEvidenceValidation",
                    "ragEvidenceInsufficient", "ragEvidenceConflict",
                    "selectedSkillKey", "skillDisclosurePhase",
                    "nodeResultType", "rerouteIntent", "clarificationQuestion", "failureReason",
                    "answer", "evidence", "toolResults", "status", "businessTaskId", "confirmToken",
                    "multiCandidate", "selectedCandidateId")) map.put(key, new ReplaceStrategy());
            return map;
        };
    }

    /** 明确列出业务子图可见字段，避免父 Graph 后续新增敏感状态时被自动泄露到全部 Agent。 */
    private Map<String, Object> project(Intent intent, OverAllState parent) {
        Map<String, Object> projected = new HashMap<>();
        for (String key : SUBGRAPH_INPUT_KEYS) {
            parent.value(key).ifPresent(value -> projected.put(key, value));
        }
        projected.put("intent", intent.name());

        /*
         * 第二阶段发生在路由完成之后。这里使用父 Graph 中的服务端身份构造 MemoryScope，再按具体
         * Agent 投影情景记忆和画像。主阶段的 Memory 不直接沿用，避免把“MAIN 可见”误当作
         * “所有业务 Agent 可见”；店铺/官方会话上下文属于接入层可信资料，单独保留。
         */
        List<String> businessContext = new java.util.ArrayList<>();
        parent.<List<String>>value("recentMessages").orElse(List.of()).stream()
                .filter(value -> value != null && (value.startsWith("STORE_CONTEXT:")
                        || value.startsWith("OFFICIAL_CONTEXT:")))
                .forEach(businessContext::add);
        MemoryScope scope = new MemoryScope(parent.value("tenantId", ""), parent.value("userId", ""),
                parent.value("conversationId", ""), parent.value("runId", ""),
                parent.value("subRunId", ""), parent.value("businessTaskId", ""), intent.name(),
                "ANSWER_GENERATION", "WEB");
        businessContext.addAll(memory.buildBusinessContext(scope, parent.value("content", ""),
                parent.value("traceId", "")).legacyLines());
        projected.put("recentMessages", List.copyOf(businessContext));
        return projected;
    }

    /** 把领域执行结果转换成主图只认识的 NodeResultType 协议。 */
    private Map<String, Object> result(AgentResult result) {
        Map<String, Object> map = new HashMap<>();
        NodeResultType resultType = switch (result.status()) {
            case COMPLETED -> NodeResultType.SUCCESS;
            case NEED_CLARIFICATION -> NodeResultType.NEED_MORE_INFORMATION;
            case WAITING_CONFIRMATION -> NodeResultType.NEED_USER_CONFIRMATION;
            case WAITING_STAFF_APPROVAL, HANDOFF -> NodeResultType.NEED_HUMAN;
            case BLOCKED -> NodeResultType.BLOCKED;
            case FAILED -> NodeResultType.RETRYABLE_FAILURE;
        };
        map.put("nodeResultType", resultType.name());
        map.put("rerouteIntent", Intent.UNKNOWN.name());
        map.put("clarificationQuestion",
                resultType == NodeResultType.NEED_MORE_INFORMATION ? result.answer() : "");
        map.put("failureReason", resultType == NodeResultType.RETRYABLE_FAILURE
                ? "领域子 Graph 返回可重试失败" : "");
        map.put("answer", result.answer());
        map.put("evidence", result.evidence());
        map.put("toolResults", result.toolResults());
        map.put("status", result.status().name());
        map.put("businessTaskId", result.businessTaskId());
        map.put("confirmToken", result.confirmToken());
        map.put("multiCandidate", result.multiCandidate());
        map.put("selectedCandidateId", result.selectedCandidateId());
        return map;
    }

    /** 从已完成的领域子图状态还原统一返回对象，供主图决定输出、澄清、确认或人工接管。 */
    @SuppressWarnings("unchecked")
    private SubGraphResult fromState(OverAllState state) {
        ExecutionStatus executionStatus = parseStatus(state.value("status", "FAILED"));
        return new SubGraphResult(
                parseResultType(state.value("nodeResultType", "RETRYABLE_FAILURE")),
                parseIntent(state.value("rerouteIntent", "UNKNOWN")),
                state.value("answer", ""),
                state.value("clarificationQuestion", ""),
                state.<String>value("businessTaskId").orElse(null),
                state.value("failureReason", ""),
                executionStatus,
                state.value("evidence").map(value -> (List<String>) value).orElse(List.of()),
                state.value("toolResults").map(value -> (List<String>) value).orElse(List.of()),
                state.<String>value("confirmToken").orElse(null),
                state.value("multiCandidate", false),
                state.<String>value("selectedCandidateId").orElse(null));
    }

    private ExecutionStatus parseStatus(String value) {
        try { return ExecutionStatus.valueOf(value); }
        catch (IllegalArgumentException error) { return ExecutionStatus.FAILED; }
    }

    private NodeResultType parseResultType(String value) {
        try { return NodeResultType.valueOf(value); }
        catch (Exception error) { return NodeResultType.RETRYABLE_FAILURE; }
    }

    private Intent parseIntent(String value) {
        try { return Intent.valueOf(value); }
        catch (Exception error) { return Intent.UNKNOWN; }
    }
}
