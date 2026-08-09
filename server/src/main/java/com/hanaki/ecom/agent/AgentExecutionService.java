package com.hanaki.ecom.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hanaki.ecom.context.ContextAssemblyRequest;
import com.hanaki.ecom.context.ContextNode;
import com.hanaki.ecom.context.SkillDisclosurePhase;
import com.hanaki.ecom.context.RetrievedChunkRef;
import com.hanaki.ecom.commerce.RefundRequestService;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.AgentResult;
import com.hanaki.ecom.domain.Domain.CandidateProfile;
import com.hanaki.ecom.domain.Domain.ExecutionStatus;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.EvaluationDecision;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.JudgeOutcome;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.domain.Domain.RefundAssessmentView;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.rag.RagPipelineModels.EvidenceValidationResult;
import com.hanaki.ecom.rag.RagPipelineModels.FusionResult;
import com.hanaki.ecom.rag.RagPipelineModels.QueryRewriteResult;
import com.hanaki.ecom.rag.RagPipelineModels.RecallResult;
import com.hanaki.ecom.rag.RagPipelineModels.RerankResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.rag.RagQueryRewriteService;
import com.hanaki.ecom.rag.RagQueryRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 业务 SubGraph 的模型执行层。这里不再包含固定回复模板：每个候选都通过
 * Spring AI ChatModel 生成，并可调用绑定当前租户和用户的只读工具。
 */
@Service
public class AgentExecutionService {
    private final EcommerceStore store;
    private final HybridRagService rag;
    private final ToolGateway tools;
    private final RefundRequestService refunds;
    private final LogisticsAgentService logistics;
    private final AiModelGateway model;
    private final AgentTelemetryService telemetry;
    private final EvaluationStore evaluations;
    private final CandidateGenerationService candidateGenerator;
    private final BestOfThreeGraphService bestOfThree;
    private final EvaluationTriggerService evaluationTrigger;
    private final CandidateHardValidator candidateValidator;
    private final TokenBudgetEstimator tokenEstimator;
    private final VersionedAgentCache nodeCache;
    private final RagQueryRewriteService ragRewrite;
    private final RagQueryRouter ragRouter;
    private final int qualityThreshold;
    private final int scoreGapThreshold;
    private final String promptVersion;
    private final String modelVersion;
    private final long rewriteTimeoutMillis;

    public AgentExecutionService(EcommerceStore store, HybridRagService rag, ToolGateway tools,
                                 RefundRequestService refunds,
                                 LogisticsAgentService logistics,
                                 AiModelGateway model,
                                 AgentTelemetryService telemetry,
                                 EvaluationStore evaluations,
                                 CandidateGenerationService candidateGenerator,
                                 BestOfThreeGraphService bestOfThree,
                                 EvaluationTriggerService evaluationTrigger,
                                 CandidateHardValidator candidateValidator,
                                 TokenBudgetEstimator tokenEstimator,
                                 VersionedAgentCache nodeCache,
                                 RagQueryRewriteService ragRewrite,
                                 RagQueryRouter ragRouter,
                                 @Value("${agent.judge.minimum-score:75}") int qualityThreshold,
                                 @Value("${agent.judge.minimum-gap:5}") int scoreGapThreshold,
                                 @Value("${agent.observability.prompt-version:local}") String promptVersion,
                                 @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String modelVersion,
                                 @Value("${agent.rag.rewrite.timeout-millis:4500}") long rewriteTimeoutMillis) {
        this.store = store;
        this.rag = rag;
        this.tools = tools;
        this.refunds = refunds;
        this.logistics = logistics;
        this.model = model;
        this.telemetry = telemetry;
        this.evaluations = evaluations;
        this.candidateGenerator = candidateGenerator;
        this.bestOfThree = bestOfThree;
        this.evaluationTrigger = evaluationTrigger;
        this.candidateValidator = candidateValidator;
        this.tokenEstimator = tokenEstimator;
        this.nodeCache = nodeCache;
        this.ragRewrite = ragRewrite;
        this.ragRouter = ragRouter;
        this.qualityThreshold = qualityThreshold;
        this.scoreGapThreshold = scoreGapThreshold;
        this.promptVersion = promptVersion;
        this.modelVersion = modelVersion;
        this.rewriteTimeoutMillis = Math.max(100L, rewriteTimeoutMillis);
    }

    /**
     * RAG 子图第一步：生成结构化 Query Rewrite。
     *
     * <p>模型调用失败时只回退到用户原问题，不尝试用异常输出继续检索；随后由 RagQueryRewriteService
     * 重新绑定可信实体并判定是否需要澄清。Graph State 同时保留 rewrittenQuery 字符串，是为了兼容
     * 现有回答/评审节点；真正的检索节点只读取 ragRewrite 结构，不再读取自由文本控制字段。</p>
     */
    public Map<String, Object> rewriteRagQuery(OverAllState state, Intent intent) {
        String message = state.value("content", "");
        List<String> recent = strings(state, "recentMessages");
        boolean skipKnowledge = intent == Intent.IN_SALE && logistics.matches(message, recent);
        String rewritten = message;
        boolean modelFailed = false;
        if (!skipKnowledge) {
            try {
                rewritten = rewriteWithTimeout(state, intent, message, recent);
            } catch (RuntimeException error) {
                /*
                 * 改写模型属于可降级读依赖。保留原问题比继续使用半截 JSON、空串或模型错误文本安全；
                 * fallbackUsed 会进入结构化状态，使缓存、指标和离线评测能区分正常改写与降级改写。
                 */
                modelFailed = true;
                rewritten = message;
            }
        }
        QueryRewriteResult structured = ragRewrite.build(message, rewritten, recent,
                stringMap(state, "entities"), modelVersion, modelFailed);
        Map<String, Object> result = new HashMap<>();
        result.put("rewrittenQuery", structured.standaloneQuestion());
        result.put("ragRewrite", structured);
        result.put("ragSkipKnowledge", skipKnowledge);
        result.put("ragClarificationRequired", structured.clarificationRequired());
        return result;
    }

    /**
     * 第二步：将改写结果映射为服务端可信 RetrievalPlan。路由器看不到 tenantId、索引名、凭据或
     * Elasticsearch DSL，因此模型/用户只能影响固定枚举内的业务意图，不能绕过租户及有效期过滤。
     */
    public Map<String, Object> routeRagQuery(OverAllState state, Intent intent) {
        QueryRewriteResult rewrite = required(state, "ragRewrite", QueryRewriteResult.class);
        RetrievalPlan plan = ragRouter.route(intent, rewrite, state.value("ragSkipKnowledge", false));
        return Map.of("ragPlan", plan, "ragRoutingReason", plan.routingReason());
    }

    /** 第三步：并行执行计划中启用的各召回分支，输出分来源的轻量候选。 */
    public Map<String, Object> recallRag(OverAllState state) {
        RetrievalPlan plan = required(state, "ragPlan", RetrievalPlan.class);
        QueryRewriteResult rewrite = required(state, "ragRewrite", QueryRewriteResult.class);
        RecallResult recalled = rag.recall(state.value("tenantId", ""), plan, rewrite);
        return Map.of("ragRecall", recalled);
    }

    /** 第四步：只按各分支 rank 执行加权 RRF，不直接相加 BM25/向量原始分数。 */
    public Map<String, Object> fuseRag(OverAllState state) {
        RetrievalPlan plan = required(state, "ragPlan", RetrievalPlan.class);
        RecallResult recalled = required(state, "ragRecall", RecallResult.class);
        FusionResult fused = rag.fuse(state.value("tenantId", ""), plan, recalled);
        return Map.of("ragFusion", fused);
    }

    /** 第五步：对 RRF Top-N 重排；远程失败或非法响应时显式回退 RRF 顺序。 */
    public Map<String, Object> rerankRag(OverAllState state) {
        RetrievalPlan plan = required(state, "ragPlan", RetrievalPlan.class);
        QueryRewriteResult rewrite = required(state, "ragRewrite", QueryRewriteResult.class);
        FusionResult fused = required(state, "ragFusion", FusionResult.class);
        RerankResult reranked = rag.rerank(state.value("tenantId", ""), plan, rewrite, fused);
        return Map.of("ragRerank", reranked);
    }

    /**
     * 第六步：验证租户、版本、最低分数、子问题覆盖、提示注入与政策冲突。只有 approvedReferences
     * 会进入 retrievedChunkRefs；正文仍要等回答节点按当前权威源重新物化。
     */
    public Map<String, Object> validateRagEvidence(OverAllState state) {
        RetrievalPlan plan = required(state, "ragPlan", RetrievalPlan.class);
        QueryRewriteResult rewrite = required(state, "ragRewrite", QueryRewriteResult.class);
        RerankResult reranked = required(state, "ragRerank", RerankResult.class);
        EvidenceValidationResult validated = rag.validate(state.value("tenantId", ""), plan, rewrite, reranked);
        Map<String, Object> result = new HashMap<>();
        result.put("ragEvidenceValidation", validated);
        result.put("retrievedChunkRefs", validated.approvedReferences());
        result.put("ragEvidenceInsufficient", validated.insufficient());
        result.put("ragEvidenceConflict", validated.conflict());
        return result;
    }

    /**
     * 只缓存“查询改写”这个确定性读节点，不缓存候选回答、Judge 或任何写操作。输入投影显式包含
     * 当前问题、意图和会话历史；整份 Graph State 不进入 Key。USER 作用域防止含个人上下文的改写
     * 跨用户复用，Prompt/模型版本变化也会进入新的 versionDigest。
     */
    private String rewriteWithNodeCache(OverAllState state, Intent intent,
                                        String message, List<String> recent) {
        String tenant = state.value("tenantId", "");
        String user = state.value("userId", "");
        if (tenant.isBlank() || user.isBlank()) return model.rewrite(message, recent, intent);
        Map<String, Object> rewriteFacts = new HashMap<>();
        state.value("entities").ifPresent(value -> rewriteFacts.put("confirmedEntities", value));
        rewriteFacts.put("businessStage", "QUERY_REWRITE");
        ContextAssemblyRequest modelRequest = new ContextAssemblyRequest(TrustedContexts.from(state), intent,
                ContextNode.QUERY_REWRITE, 0, message, "", recent, List.of(), rewriteFacts, List.of(),
                "", SkillDisclosurePhase.NONE, modelVersion, 0);
        String resourceProjection = "intent=" + intent.name() + "|message=" + QueryNormalizer.normalize(message)
                + "|recent=" + recent.stream().map(QueryNormalizer::normalize)
                .reduce((left, right) -> left + "\u001f" + right).orElse("");
        CacheContext context = new CacheContext(tenant, user, state.value("conversationId", ""),
                state.value("runId", ""), intent.name(), "query_rewrite", "conversation:read",
                "", promptVersion, modelVersion, "", 0L, state.value("traceId", ""));
        return nodeCache.getOrLoad(CachePolicy.userScopedNode(), context, resourceProjection,
                        "rewrite|" + promptVersion + "|" + modelVersion, new TypeReference<String>() {},
                        value -> value != null && !value.isBlank() && value.length() <= 4_000,
                        () -> CacheLoadResult.success(model.rewrite(modelRequest), "rewrite-model"))
                .orElseThrow(() -> new IllegalStateException("查询改写节点没有返回结果"));
    }

    /**
     * 给改写节点设置独立硬超时。超时只影响 Query Rewrite，后续可用原问题继续保守检索；不会因为
     * 一个非必要模型依赖卡住整个 Business Agent。结构化字段的“修复”由 RagQueryRewriteService
     * 确定性完成一次（补回实体、生成两类查询、修剪长度），修复后仍不合法才由调用方回退原问题。
     */
    private String rewriteWithTimeout(OverAllState state, Intent intent,
                                      String message, List<String> recent) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return CompletableFuture.supplyAsync(
                            () -> rewriteWithNodeCache(state, intent, message, recent), executor)
                    .orTimeout(rewriteTimeoutMillis, TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("查询改写超时或失败", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * SubGraph 第二步：简单问题调用一次模型；复杂问题冻结相同上下文并并行生成三份候选，
     * 最后交给独立模型 Judge。候选阶段只读，不创建工单或退款任务。
     */
    public AgentResult generate(OverAllState state, Intent intent) {
        String message = state.value("content", "");
        String tenant = state.value("tenantId", "");
        String user = state.value("userId", "");
        String rewritten = state.value("rewrittenQuery", message);
        List<String> recent = strings(state, "recentMessages");
        /*
         * 领域 Agent 不能直接创建人工工单。即使用户在进入子 Graph 后再次表达转人工，也只返回
         * HANDOFF 固定状态，由主 Graph 的 humanHandoffNode 统一完成队列选择、幂等与审计。
         * 这条约束阻止业务 Agent 相互调用，也避免人工接管被错误地绑定在投诉 Agent 内部。
         */
        if (requestsHuman(message) || state.value("forceHandoff", false)) {
            return new AgentResult("已收到人工处理诉求，正在交回客服主流程安排接管。",
                    List.of(), List.of("SUBGRAPH_RESULT:NEED_HUMAN"),
                    ExecutionStatus.HANDOFF, null, null, false, "NEED_HUMAN");
        }
        OrderQueryScope orderScope = OrderQueryScope.resolve(
                state.value("channelKind", "GENERAL"), state.value("storeId", ""),
                state.value("productId", ""), state.value("productName", ""), message);
        if (intent == Intent.IN_SALE && logistics.matches(message, recent)) {
            return logistics.answer(tenant, user, message, orderScope);
        }
        EvidenceValidationResult evidenceDecision = state.<EvidenceValidationResult>value("ragEvidenceValidation")
                .orElse(null);
        if (state.value("ragClarificationRequired", false)) {
            QueryRewriteResult rewrite = state.<QueryRewriteResult>value("ragRewrite").orElse(null);
            String reason = rewrite == null || rewrite.clarificationReason().isBlank()
                    ? "需要明确具体商品、型号或订单" : rewrite.clarificationReason();
            return new AgentResult("为了准确查询，请补充具体商品名称、型号、SKU 或相关订单号。",
                    List.of(reason), List.of("RAG_QUERY_REWRITE:NEED_CLARIFICATION"),
                    ExecutionStatus.NEED_CLARIFICATION, null, null, false, "RAG_CLARIFICATION");
        }
        if (evidenceDecision != null && evidenceDecision.conflict()) {
            return new AgentResult("检索到的有效政策存在冲突，当前无法安全地给出确定结论，请补充商品和订单信息或转人工核实。",
                    evidenceDecision.reasons(), List.of("RAG_EVIDENCE:CONFLICT"),
                    ExecutionStatus.NEED_CLARIFICATION, null, null, false, "RAG_POLICY_CONFLICT");
        }
        if (evidenceDecision != null && evidenceDecision.insufficient()) {
            return new AgentResult("目前没有找到足以直接支持结论的有效证据，请补充具体商品、问题发生时间和订单信息。",
                    evidenceDecision.reasons(), List.of("RAG_EVIDENCE:INSUFFICIENT"),
                    ExecutionStatus.NEED_CLARIFICATION, null, null, false, "RAG_INSUFFICIENT_EVIDENCE");
        }
        List<KnowledgeDoc> evidence = knowledge(state, intent);
        EvaluationContextSnapshot snapshot;
        /*
         * batchId 绑定本次 Graph run，同一请求恢复时保持稳定，不同请求不会共享评审。进入触发器之前
         * 先把候选可能需要的订单/物流事实查出并投影；三个候选之后只读取这份快照，避免并行期间
         * 订单状态变化造成“候选 A 看到已发货、候选 B 看到待发货”的不可比较结果。
         */
        String batchId = "eval-" + state.value("runId", "unknown");
        List<OrderSummary> recentOrders = (intent == Intent.IN_SALE || intent == Intent.AFTER_SALE || intent == Intent.COMPLAINT)
                ? orders(tenant, user, orderScope) : List.of();
        Map<String, Object> frozenFacts = new HashMap<>();
        frozenFacts.put("recentOrders", recentOrders.stream().map(order -> Map.<String, Object>ofEntries(
                Map.entry("orderId", order.id()), Map.entry("productId", order.productId()),
                Map.entry("productName", order.productName()),
                Map.entry("sku", order.sku() == null ? "" : order.sku()),
                Map.entry("amount", order.amount()), Map.entry("orderStatus", order.status()),
                Map.entry("paymentStatus", order.paymentStatus()),
                Map.entry("logisticsStatus", order.logisticsStatus()),
                Map.entry("storeName", order.storeName() == null ? "" : order.storeName()),
                Map.entry("plannedShipAt", order.plannedShipAt() == null ? "" : order.plannedShipAt().toString()),
                Map.entry("estimatedArrivalAt", order.estimatedArrivalAt() == null ? "" : order.estimatedArrivalAt().toString()),
                Map.entry("createdAt", order.createdAt().toString()))).toList());
        frozenFacts.put("rewrittenQuery", rewritten);
        frozenFacts.put("modelVersion", modelVersion);
        frozenFacts.put("selectedSkillKey", state.value("selectedSkillKey", ""));
        frozenFacts.put("orderQueryScope", orderScope.mode().name());
        frozenFacts.put("scopeStoreId", orderScope.storeId());
        frozenFacts.put("scopeProductId", orderScope.productId());
        frozenFacts.put("scopeProductName", orderScope.productName());
        frozenFacts.put("source", "ECOMMERCE_STORE");
        frozenFacts.put("version", "order-projection-v3");
        frozenFacts.put("fetchedAt", Instant.now().toString());
        Map<String, Object> logisticsByOrder = new HashMap<>();
        if (intent == Intent.IN_SALE || intent == Intent.COMPLAINT) {
            recentOrders.stream().limit(5).forEach(order -> logisticsByOrder.put(order.id(),
                    List.copyOf(store.logistics(tenant, user, order.id()))));
        }
        frozenFacts.put("logisticsByOrder", Map.copyOf(logisticsByOrder));
        snapshot = new EvaluationContextSnapshot(batchId, tenant, user,
                state.value("conversationId", ""), state.value("runId", ""), message, intent,
                List.copyOf(recent), List.copyOf(evidence),
                Map.copyOf(frozenFacts),
                evidence.stream().map(KnowledgeDoc::version).sorted().distinct()
                        .reduce((a, b) -> a + "," + b).orElse("none"),
                "after-sale-v12", promptVersion, List.of(state.value("riskLevel", "LOW")), Instant.now());

        // 预算判断在产生 3 倍模型成本之前完成；估算包含问题、历史、证据和冻结业务事实。
        int estimatedPromptTokens = estimateEvaluationTokens(message, rewritten, recent, evidence, frozenFacts);
        double routeConfidence = ((Number) state.value("confidence", 1d)).doubleValue();
        EvaluationDecision decision = evaluationTrigger.beforeGeneration(message, intent, routeConfidence,
                evidence.size(), estimatedPromptTokens);
        AgentDraft selected;
        boolean reviewRequired = false;
        boolean multiCandidate = decision.required();
        if (decision.required()) {
            // 前置触发：请求本身已复杂/高风险，跳过单候选，直接进入共享快照的 Best-of-3。
            telemetry.observeTrace(state.value("traceId", ""), "evaluation.freeze", "CHAIN",
                    Map.of("evaluationBatchId", batchId, "triggerMode", decision.triggerMode().name()),
                    Map.of("evidenceCount", evidence.size(), "triggerReason", decision.reason()), () -> snapshot);
            JudgeOutcome outcome = bestOfThree.run(snapshot, state.value("traceId", ""), decision);
            selected = outcome.winner();
            reviewRequired = outcome.needsHumanReview();
        } else {
            /*
             * 简单请求先生成一份事实优先基线，降低平均延迟与 Token。基线随后仍经过同一硬校验；
             * 若质量不足则走 POST_GENERATION，把这次已付费结果登记为 candidate 1 后再补另外分支，
             * 避免重复生成基线。
             */
            CandidateGenerationService.GeneratedCandidate baseline = telemetry.observeCandidate(
                    state.value("traceId", ""), 1,
                    Map.of("intent", intent.name()),
                    () -> candidateGenerator.generate(snapshot, CandidateProfile.FACT_AND_EVIDENCE.variant(),
                            new ConcurrentHashMap<>()));
            CandidateHardValidator.Validation validation = candidateValidator.validate(snapshot, baseline.draft());
            EvaluationDecision postDecision = evaluationTrigger.afterGeneration(baseline.draft(), validation,
                    intent, estimatedPromptTokens);
            if (postDecision.required()) {
                multiCandidate = true;
                // 先持久化基线 attempt，再调用可恢复编排；BestOfThree 会复用 candidateNo=1。
                evaluations.createBatch(snapshot, state.value("traceId", ""), postDecision,
                        qualityThreshold, scoreGapThreshold);
                EvaluationContextSnapshot sealed = evaluations.freezeSnapshot(snapshot);
                EvaluationStore.CandidateAttempt baselineAttempt = evaluations.startCandidateAttempt(sealed, 1,
                                CandidateProfile.FACT_AND_EVIDENCE, 2)
                        .orElseThrow(() -> new IllegalStateException("无法登记后触发评审的基线候选"));
                evaluations.completeCandidateAttempt(baselineAttempt.id(), baseline.draft(), 0,
                        baseline.promptTokens(), baseline.completionTokens());
                evaluations.saveCandidate(sealed.evaluationBatchId(), 1, baselineAttempt.candidateRunId(),
                        baselineAttempt.id(), CandidateProfile.FACT_AND_EVIDENCE, baseline.draft(), 0,
                        validation.auditEntries(), baseline.promptTokens(), baseline.completionTokens());
                JudgeOutcome outcome = bestOfThree.run(sealed, state.value("traceId", ""), postDecision);
                selected = outcome.winner();
                reviewRequired = outcome.needsHumanReview();
            } else if (!validation.accepted()) {
                // 即使后置触发被预算/开关禁止，也绝不能直接返回未通过硬规则的基线答案。
                selected = new AgentDraft("SAFE_FALLBACK",
                        "当前答案未通过事实与权限校验。请补充具体商品或本人订单信息，或者回复“转人工”。",
                        List.of(), validation.auditEntries(), true, 5, 10);
                reviewRequired = true;
            } else {
                selected = baseline.draft();
            }
        }
        return finalizeOnce(intent, tenant, user, state.value("conversationId", ""), state.value("runId", ""),
                message, confirmedOrderId(state), orderScope, selected, multiCandidate, reviewRequired);
    }

    /**
     * 所有可能产生副作用的动作只在获胜候选确定后执行一次。
     *
     * <p>候选和 Judge 阶段均为只读；这里只根据最终赢家和实时业务校验创建一次待确认任务，并通过
     * stateRunKey 绑定业务幂等键。SAFE_FALLBACK 或需要人工复核时绝不创建退款任务。</p>
     */
    private AgentResult finalizeOnce(Intent intent, String tenant, String user, String conversationId, String runId,
                                     String message, String confirmedOrderId, OrderQueryScope orderScope,
                                     AgentDraft selected, boolean multiCandidate, boolean reviewRequired) {
        String taskId = null;
        String token = null;
        String answer = selected.answer();
        ExecutionStatus status = intent == Intent.UNKNOWN || reviewRequired
                ? ExecutionStatus.NEED_CLARIFICATION : ExecutionStatus.COMPLETED;

        if ("SAFE_FALLBACK".equals(selected.candidateId())) {
            return new AgentResult(selected.answer(), selected.evidence(), selected.toolResults(),
                    ExecutionStatus.NEED_CLARIFICATION, null, null, multiCandidate, selected.candidateId());
        }

        if (!reviewRequired && intent == Intent.AFTER_SALE && contains(message, "退货", "退款", "换货")) {
            /*
             * 高风险任务只能使用用户本轮明确给出的、由确定性实体提取器确认的完整订单号。禁止选择
             * “最近第一单”，否则多订单用户可能为错误订单创建退款任务。没有确认订单时只进入澄清，
             * 不签发确认令牌；即使模型答案声称选中了某单，也不会改变这里的服务端主键来源。
             */
            OrderSummary order = null;
            if (!confirmedOrderId.isBlank()) {
                order = orderScope.mode() == OrderQueryScope.Mode.CURRENT_USER
                        ? store.ownedOrder(tenant, user, confirmedOrderId).orElse(null)
                        : orders(tenant, user, orderScope).stream()
                        .filter(candidate -> candidate.id().equals(confirmedOrderId)).findFirst().orElse(null);
            }
            if (order == null) {
                return new AgentResult(selected.answer(), selected.evidence(), selected.toolResults(),
                        ExecutionStatus.NEED_CLARIFICATION, null, null, multiCandidate, selected.candidateId());
            }
            try {
                refunds.validateRequestable(order);
            } catch (IllegalArgumentException error) {
                return new AgentResult(error.getMessage(), selected.evidence(), selected.toolResults(),
                        ExecutionStatus.NEED_CLARIFICATION, null, null, multiCandidate, selected.candidateId());
            }
            tools.assertAllowed(intent, "preview_refund");
            String businessTenantId = order.tenantId();
            taskId = store.createBusinessTask(businessTenantId, user, order.id(), "REFUND",
                    "WAITING_CONFIRMATION", "after-sale-v12", stateRunKey(runId, conversationId, order));
            String taskStatus = store.taskStatus(taskId, businessTenantId);
            if (!"WAITING_CONFIRMATION".equals(taskStatus)) {
                answer = "该订单已有退款任务，当前状态：" + taskStatus + "。系统不会重复创建申请或退款。";
                status = "WAITING_STORE_APPROVAL".equals(taskStatus)
                        ? ExecutionStatus.WAITING_STAFF_APPROVAL : ExecutionStatus.COMPLETED;
            } else {
                RefundAssessmentView assessment = refunds.assess(taskId, businessTenantId, user,
                        order, message, List.of());
                token = tools.issueConfirmToken(businessTenantId, user, taskId, order.id(), "submit_refund",
                        order.amount(), assessment.ruleVersion());
                status = ExecutionStatus.WAITING_CONFIRMATION;
                answer = selected.answer() + "\n退款理由评分：" + assessment.score() + " 分。" +
                        assessment.summary() + ("AUTO_REFUND".equals(assessment.decisionMode())
                        ? " 确认后将自动退款。" : " 确认后将提交订单所属店铺审核。");
            }
        }
        return new AgentResult(answer, selected.evidence(), selected.toolResults(), status,
                taskId, token, multiCandidate, selected.candidateId());
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(OverAllState state, String key) {
        return state.value(key).map(value -> (List<String>) value).orElse(List.of());
    }

    private List<OrderSummary> orders(String tenantId, String userId, OrderQueryScope scope) {
        return switch (scope.mode()) {
            case CURRENT_PRODUCT -> store.recentOrdersForProduct(
                    tenantId, userId, scope.storeId(), scope.productId());
            case CURRENT_STORE -> store.recentOrdersForStore(tenantId, userId, scope.storeId());
            case CURRENT_USER -> store.recentOrders(tenantId, userId);
        };
    }

    /**
     * Graph 节点间的结构化状态必须保持类型协议。与其在下游发生难懂的 ClassCastException，这里在
     * 节点边界给出包含 key 与实际类型的错误，便于根据 checkpoint/trace 快速定位错误写入节点。
     */
    private <T> T required(OverAllState state, String key, Class<T> type) {
        Object value = state.value(key).orElseThrow(() ->
                new IllegalStateException("RAG Graph State 缺少必填字段: " + key));
        if (!type.isInstance(value))
            throw new IllegalStateException("RAG Graph State 字段 " + key + " 类型错误，期望 "
                    + type.getSimpleName() + "，实际 " + value.getClass().getSimpleName());
        return type.cast(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(OverAllState state, String key) {
        Object value = state.value(key).orElse(Map.of());
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, String> result = new HashMap<>();
        source.forEach((entryKey, entryValue) -> {
            if (entryKey instanceof String name && entryValue instanceof String text)
                result.put(name, text);
        });
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private List<KnowledgeDoc> knowledge(OverAllState state, Intent intent) {
        List<RetrievedChunkRef> references = state.value("retrievedChunkRefs")
                .map(value -> (List<RetrievedChunkRef>) value).orElse(List.of());
        return rag.materialize(state.value("tenantId", ""), intent.name(), references);
    }

    private boolean contains(String text, String... words) {
        if (text == null) return false;
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private int estimateEvaluationTokens(String message, String rewritten, List<String> recent,
                                         List<KnowledgeDoc> evidence, Map<String, Object> facts) {
        String evidenceText = evidence.stream().map(doc -> doc.title() + " " + doc.version() + " " + doc.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return tokenEstimator.estimate(message + "\n" + rewritten + "\n" + String.join("\n", recent)
                + "\n" + evidenceText + "\n" + facts);
    }

    private boolean requestsHuman(String text) {
        return contains(text, "转人工", "人工客服", "真人客服", "找客服人员", "人工处理", "人工接管");
    }

    @SuppressWarnings("unchecked")
    private String confirmedOrderId(OverAllState state) {
        Map<String, String> entities = state.value("entities")
                .map(value -> (Map<String, String>) value).orElse(Map.of());
        return entities.getOrDefault("orderId", "");
    }

    /** 获胜结果与会话/订单共同形成稳定业务幂等源，Run 恢复不会重复创建高危任务。 */
    private String stateRunKey(String runId, String conversationId, OrderSummary order) {
        return "agent-refund|" + runId + "|" + conversationId + "|" + order.id();
    }

}
