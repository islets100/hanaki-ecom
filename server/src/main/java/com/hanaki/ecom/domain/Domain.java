package com.hanaki.ecom.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * API 与 Agent 共享的强类型领域对象集中放在一处，避免模型自由文本直接进入业务层。
 * 实际大型项目可按商品、订单、客服、工单拆成独立模块。
 */
public final class Domain {
    private Domain() {}

    /**
     * 主路由允许输出的固定意图集合。
     *
     * <p>{@code HUMAN_SERVICE} 是主 Graph 的通用控制路径，不是第五个业务 Agent。它可以由售前、
     * 售中、售后、投诉或风控失败共同进入；{@code COMPLAINT} 则仍是一个有独立处理流程的业务领域。
     * 把两者拆开后，“用户投诉但仍可自动安抚/建单”和“用户明确要求真人”不会再被错误地视为同一件事。</p>
     */
    public enum Intent { PRE_SALE, IN_SALE, AFTER_SALE, COMPLAINT, HUMAN_SERVICE, UNKNOWN }

    /** 应用侧校准后的路由置信度档位；条件边只读取该枚举，不直接相信模型输出的浮点数。 */
    public enum RoutingConfidence { HIGH, MEDIUM, LOW }

    /**
     * 四个业务子 Graph 对主 Graph 使用的统一返回协议。
     * 领域 Agent 不能通过返回任意节点名改变拓扑，只能从这些固定结果中选择一个。
     */
    public enum NodeResultType {
        SUCCESS,
        NEED_MORE_INFORMATION,
        NEED_USER_CONFIRMATION,
        RETRYABLE_FAILURE,
        NEED_REROUTE,
        NEED_HUMAN,
        BLOCKED
    }

    /** Graph 运行状态与订单/退款等业务状态分离，避免把流程快照误当成真实业务结果。 */
    public enum RunStatus { INITIALIZED, RUNNING, WAITING, COMPLETED, BLOCKED, FAILED }

    /** 外部写操作流水状态；UNKNOWN 表示超时后无法确认结果，恢复时必须先查单。 */
    public enum ToolOperationStatus { INIT, EXECUTING, SUCCEEDED, FAILED, UNKNOWN }

    /** 用户确认记录的生命周期；确认并不等同于业务写操作已经执行。 */
    public enum ConfirmationStatus { PENDING, CONFIRMED, CANCELLED, EXPIRED }
    public enum RiskLevel { LOW, MEDIUM, HIGH, BLOCKED }
    public enum ExecutionStatus { COMPLETED, NEED_CLARIFICATION, WAITING_CONFIRMATION, WAITING_STAFF_APPROVAL, HANDOFF, BLOCKED, FAILED }
    public enum AccountRole { CUSTOMER, STORE_ADMIN, STORE_AGENT, OFFICIAL_AGENT }
    /** NONE=单候选；PRE=生成前因复杂度触发；POST=基线质量不足后补充候选。 */
    public enum EvaluationTriggerMode { NONE, PRE_GENERATION, POST_GENERATION }

    /**
     * 三个编号固定的生成画像。画像只调整关注重点，不能改变事实快照、权限、可用工具或安全规则；
     * candidateNo 到画像的映射由服务端 switch 固定，便于回放时解释每个分支为何不同。
     */
    public enum CandidateProfile {
        FACT_AND_EVIDENCE(1), PROCESS_AND_EDGE_CASES(2), UX_AND_ACTIONABILITY(3);

        private final int variant;
        CandidateProfile(int variant) { this.variant = variant; }
        public int variant() { return variant; }
        public static CandidateProfile forCandidate(int candidateNo) {
            return switch (candidateNo) {
                case 1 -> FACT_AND_EVIDENCE;
                case 2 -> PROCESS_AND_EDGE_CASES;
                case 3 -> UX_AND_ACTIONABILITY;
                default -> throw new IllegalArgumentException("candidateNo 必须为 1、2 或 3");
            };
        }
    }

    /**
     * 是否进入多候选评审由应用侧确定性规则决定，并把前置/后置模式、触发原因和配置画像版本
     * 持久化。模型只能提供路由置信度等输入信号，不能自行扩大执行成本。
     */
    public record EvaluationDecision(boolean required, EvaluationTriggerMode triggerMode,
                                     String reason, String evaluationProfile) {
        public static EvaluationDecision single(String reason) {
            return new EvaluationDecision(false, EvaluationTriggerMode.NONE, reason, "single-v1");
        }
    }

    public record Product(String id, String tenantId, String storeId, String name, String subtitle, String category,
                          BigDecimal price, BigDecimal oldPrice, int stock, String badge, String attributesJson) {}

    public record MerchantStore(String id, String tenantId, String name, String logoText, String description,
                                BigDecimal serviceScore, BigDecimal fulfillmentScore, String location) {}
    public record MerchantProductView(String id, String tenantId, String storeId, String name, String subtitle,
                                      String category, BigDecimal price, BigDecimal oldPrice, int stock,
                                      String badge, String attributesJson, boolean active) {}
    public record MerchantOverview(MerchantStore store, List<MerchantProductView> products) {}
    public record MerchantStoreUpdateRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 12) String logoText,
            @NotBlank @Size(max = 2000) String description,
            @NotBlank @Size(max = 120) String location) {}
    public record MerchantProductUpsertRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 255) String subtitle,
            @NotBlank @Size(max = 80) String category,
            @NotNull @DecimalMin(value = "0.01") BigDecimal price,
            @DecimalMin(value = "0.01") BigDecimal oldPrice,
            @Min(0) int stock,
            @Size(max = 80) String badge,
            @NotBlank @Size(max = 10000) String attributesJson) {}
    public record MerchantProductStatusRequest(boolean active) {}
    public record ProductDetail(Product product, MerchantStore store, List<Product> otherProducts) {}

    public record OrderSummary(String id, String maskedId, String tenantId, String userId, String productId,
                               String productName, String sku, BigDecimal amount, String status,
                               String paymentStatus, String logisticsStatus, String storeName,
                               Instant plannedShipAt, Instant estimatedArrivalAt, Instant createdAt) {}

    public record LogisticsEvent(String time, String location, String description) {}
    public record FulfillmentView(String orderId, String storeId, String status, Instant plannedShipAt,
                                  Instant estimatedArrivalAt, Instant shippedAt, Instant deliveredAt,
                                  List<LogisticsEvent> events) {}

    public record BalanceView(BigDecimal availableBalance, String currency, int version) {}
    public record BalanceEntryView(String id, String entryType, BigDecimal amount, BigDecimal balanceAfter,
                                   String referenceId, String description, Instant createdAt) {}
    public record PlatformBalanceView(String tenantId, BigDecimal availableBalance, String currency, int version) {}
    public record PlatformBalanceEntryView(String id, String entryType, BigDecimal amount,
                                           BigDecimal balanceAfter, String referenceId,
                                           String description, Instant createdAt) {}
    public record PurchaseRequest(@NotBlank @Size(max = 40) String productId,
                                  @Size(max = 120) String sku,
                                  @Pattern(regexp = "[A-Za-z0-9._:-]{1,120}") String requestId) {
        public PurchaseRequest(String productId, String sku) { this(productId, sku, null); }
    }
    public record PurchaseResponse(String orderId, BigDecimal paidAmount, BigDecimal balanceAfter,
                                   Instant plannedShipAt, Instant estimatedArrivalAt, String status) {}

    public record KnowledgeDoc(String id, String tenantId, String domain, String title, String content,
                               String version, double score) {}

    /**
     * 主 Agent 的强类型输出。
     *
     * <p>{@code modelSelfConfidence} 是大模型未经校准的自评分；{@code confidence} 才是应用侧结合
     * 候选差值、确定性规则和上下文一致性得到的最终路由分值。Graph 条件边只使用最终分值对应的
     * {@code confidenceBand}，从而避免模型简单输出一个 0.99 就绕过澄清节点。</p>
     */
    public record RouteResult(Intent intent, Intent secondaryIntent,
                              double modelSelfConfidence, double confidence,
                              RoutingConfidence confidenceBand, List<Intent> candidates,
                              Map<String, String> entities, boolean needClarification, String reason) {
        /** 保留给简单测试和非 Graph 调用的兼容构造器。 */
        public RouteResult(Intent intent, double confidence, List<Intent> candidates,
                           Map<String, String> entities, boolean needClarification, String reason) {
            this(intent, Intent.UNKNOWN, confidence, confidence,
                    confidence >= 0.82 ? RoutingConfidence.HIGH
                            : confidence >= 0.55 ? RoutingConfidence.MEDIUM : RoutingConfidence.LOW,
                    candidates, entities, needClarification, reason);
        }
    }

    /**
     * Spring AI 结构化输出。模型返回第一、第二候选及各自的自评置信度；这些值只是校准输入，
     * 既不是 Graph 节点名，也不直接决定条件边。三参数构造器用于兼容已有 Mock 和历史快照。
     */
    public record ModelRoute(String intent, String secondaryIntent,
                             double confidence, double secondaryConfidence, String reason) {
        public ModelRoute(String intent, double confidence, String reason) {
            this(intent, "UNKNOWN", confidence, 0d, reason);
        }
    }

    /** Query Rewrite 的结构化结果，避免从自由文本中猜测实际检索词。 */
    public record ModelRewrite(String rewrittenQuery) {}

    /** Memory 模型只能提出候选，是否确认和写入仍由服务端白名单与规则决定。 */
    public record ModelMemoryFact(String factKey, String factValue, String memoryType,
                                  boolean explicitlyConfirmed, double confidence, int ttlDays) {}
    public record ModelMemoryExtraction(List<ModelMemoryFact> facts) {}
    public record ModelConversationSummary(String summary, List<String> unresolvedQuestions) {}

    /** 业务 Agent 的模型输出；证据会由服务端与真实召回结果再次取交集。 */
    public record ModelAnswer(String answer, List<String> citedEvidence,
                              int completeness, int clarity) {}

    /**
     * Judge 对每个匿名候选的结构化分项评分。total 是模型返回协议的兼容字段，服务端不会信任它，
     * 而会夹紧所有分项、按持久化权重重算；hardViolations 或低安全分会把总分直接归零。
     */
    public record JudgeCandidateScore(String candidateId, int factuality, int evidenceSupport,
                                      int completeness, int businessConsistency, int safety,
                                      int clarity, int total, String reason,
                                      List<String> hardViolations) {}

    /**
     * 独立 Judge 不能改写答案、不能调用工具，只能从候选 ID 白名单中选择并说明是否需要人工复核。
     * selectedCandidateId 与 scoreGap 最终仍由 Java 根据重算排名校验。
     */
    public record ModelJudge(List<JudgeCandidateScore> scores, String selectedCandidateId,
                             int scoreGap, boolean needsHumanReview, String reason) {}

    /**
     * Best-of-3 三个分支共享的不可变事实快照。身份、问题、历史、证据、动态工具事实以及知识、
     * 规则、Prompt 版本全部参与 snapshotHash；首次冻结后不可更新，恢复时重新计算哈希。
     */
    public record EvaluationContextSnapshot(String evaluationBatchId, String tenantId, String userId,
                                             String conversationId, String runId, String originalQuestion,
                                             Intent intent, List<String> recentMessages,
                                             List<KnowledgeDoc> evidence, Map<String, Object> businessFacts,
                                             String knowledgeVersion, String ruleVersion,
                                             String promptVersion, List<String> riskTags, Instant createdAt,
                                             String snapshotId, String snapshotHash) {
        public EvaluationContextSnapshot {
            recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
            evidence = knowledgeDocs(evidence);
            businessFacts = businessFacts == null ? Map.of() : Map.copyOf(businessFacts);
            riskTags = riskTags == null ? List.of() : List.copyOf(riskTags);
        }

        /** 兼容简单生成与旧调用；正式评审会在 freeze_snapshot 节点补齐并校验哈希。 */
        public EvaluationContextSnapshot(String evaluationBatchId, String tenantId, String userId,
                                         String conversationId, String runId, String originalQuestion,
                                         Intent intent, List<String> recentMessages,
                                         List<KnowledgeDoc> evidence, Map<String, Object> businessFacts,
                                         String knowledgeVersion, String ruleVersion,
                                         String promptVersion, List<String> riskTags, Instant createdAt) {
            this(evaluationBatchId, tenantId, userId, conversationId, runId, originalQuestion, intent,
                    recentMessages, evidence, businessFacts, knowledgeVersion, ruleVersion, promptVersion,
                    riskTags, createdAt, evaluationBatchId + "-snapshot", "");
        }
    }

    /**
     * Graph checkpoint restoration keeps the snapshot record but materializes generic evidence
     * elements as maps. Restore those elements at the record boundary before the next checkpoint
     * asks Jackson to use the declared KnowledgeDoc serializer again.
     */
    private static List<KnowledgeDoc> knowledgeDocs(List<?> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<KnowledgeDoc> result = new ArrayList<>(source.size());
        for (Object value : source) {
            if (value instanceof KnowledgeDoc document) {
                result.add(document);
                continue;
            }
            if (!(value instanceof Map<?, ?> fields))
                throw invalidCheckpointValue("KnowledgeDoc", null, value);
            result.add(new KnowledgeDoc(
                    checkpointString(fields, "id", "KnowledgeDoc"),
                    checkpointString(fields, "tenantId", "KnowledgeDoc"),
                    checkpointString(fields, "domain", "KnowledgeDoc"),
                    checkpointString(fields, "title", "KnowledgeDoc"),
                    checkpointString(fields, "content", "KnowledgeDoc"),
                    checkpointString(fields, "version", "KnowledgeDoc"),
                    checkpointDouble(fields, "score", "KnowledgeDoc")));
        }
        return List.copyOf(result);
    }

    private static String checkpointString(Map<?, ?> fields, String field, String type) {
        Object value = fields.get(field);
        if (value instanceof String text) return text;
        throw invalidCheckpointValue(type, field, value);
    }

    private static double checkpointDouble(Map<?, ?> fields, String field, String type) {
        Object value = fields.get(field);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return number.doubleValue();
        throw invalidCheckpointValue(type, field, value);
    }

    private static int checkpointInt(Map<?, ?> fields, String field, String type) {
        Object value = fields.get(field);
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            if (Double.isFinite(numericValue) && numericValue == Math.rint(numericValue)
                    && numericValue >= Integer.MIN_VALUE && numericValue <= Integer.MAX_VALUE)
                return number.intValue();
        }
        throw invalidCheckpointValue(type, field, value);
    }

    private static String checkpointNullableString(Map<?, ?> fields, String field, String type) {
        Object value = fields.get(field);
        if (value == null || value instanceof String) return (String) value;
        throw invalidCheckpointValue(type, field, value);
    }

    private static List<String> checkpointStrings(Map<?, ?> fields, String field, String type) {
        Object value = fields.get(field);
        if (!(value instanceof List<?> source)) throw invalidCheckpointValue(type, field, value);
        List<String> result = new ArrayList<>(source.size());
        for (Object item : source) {
            if (!(item instanceof String text)) throw invalidCheckpointValue(type, field, item);
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalidCheckpointValue(String type, String field, Object value) {
        String location = field == null ? type : type + "." + field;
        return new IllegalArgumentException("Invalid " + location + " checkpoint value: " + String.valueOf(value));
    }

    /**
     * 服务端验证后的评审结果。winner 可能是真实候选或 SAFE_FALLBACK；scores 保留所有分项与否决；
     * scoreGap 使用服务端重算总分；needsHumanReview/fallbackReason 明确表示结果能否直接交付。
     */
    public record JudgeOutcome(AgentDraft winner, List<JudgeCandidateScore> scores, int scoreGap,
                               boolean needsHumanReview, String fallbackReason) {
        public JudgeOutcome {
            scores = judgeScores(scores);
        }
    }

    private static List<JudgeCandidateScore> judgeScores(List<?> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<JudgeCandidateScore> result = new ArrayList<>(source.size());
        for (Object value : source) {
            if (value instanceof JudgeCandidateScore score) {
                result.add(score);
                continue;
            }
            if (!(value instanceof Map<?, ?> fields))
                throw invalidCheckpointValue("JudgeCandidateScore", null, value);
            result.add(new JudgeCandidateScore(
                    checkpointString(fields, "candidateId", "JudgeCandidateScore"),
                    checkpointInt(fields, "factuality", "JudgeCandidateScore"),
                    checkpointInt(fields, "evidenceSupport", "JudgeCandidateScore"),
                    checkpointInt(fields, "completeness", "JudgeCandidateScore"),
                    checkpointInt(fields, "businessConsistency", "JudgeCandidateScore"),
                    checkpointInt(fields, "safety", "JudgeCandidateScore"),
                    checkpointInt(fields, "clarity", "JudgeCandidateScore"),
                    checkpointInt(fields, "total", "JudgeCandidateScore"),
                    checkpointNullableString(fields, "reason", "JudgeCandidateScore"),
                    checkpointStrings(fields, "hardViolations", "JudgeCandidateScore")));
        }
        return List.copyOf(result);
    }

    /**
     * 并行候选节点的统一输出。FAILED/REJECTED 分支也会进入固定引用和审计表；attemptId 指向物理
     * 调用，candidateRunId 指向隔离 checkpoint，reused 表示本次来自数据库恢复而非新模型调用。
     */
    public record CandidateRunOutput(int candidateNo, String candidateRunId, String attemptId,
                                     CandidateProfile profile, AgentDraft draft,
                                     String status, String errorType, String errorMessage,
                                     long elapsedMs, int promptTokens, int completionTokens, boolean reused) {
        public CandidateRunOutput(int candidateNo, String candidateRunId, AgentDraft draft,
                                  String status, String errorType, String errorMessage,
                                  long elapsedMs, int promptTokens, int completionTokens, boolean reused) {
            this(candidateNo, candidateRunId, null,
                    candidateNo >= 1 && candidateNo <= 3 ? CandidateProfile.forCandidate(candidateNo) : null,
                    draft, status, errorType, errorMessage, elapsedMs, promptTokens, completionTokens, reused);
        }
    }

    public record GuardResult(RiskLevel level, List<String> flags, boolean blocked,
                              boolean forceHandoff, String safeMessage) {}

    /**
     * 候选草稿只是一份待验证数据：evidence 已与真实召回取交集，toolResults 是审计引用，safe 是
     * 生成层快速标志；它还必须通过 CandidateHardValidator 和最终 Judge 才能成为客服答案。
     */
    public record AgentDraft(String candidateId, String answer, List<String> evidence,
                             List<String> toolResults, boolean safe, int completeness, int clarity) {}

    public record AgentResult(String answer, List<String> evidence, List<String> toolResults,
                              ExecutionStatus status, String businessTaskId, String confirmToken,
                              boolean multiCandidate, String selectedCandidateId) {}

    /**
     * 领域子 Graph 的统一输出。responseDraft 等文本只能作为数据返回给主 Graph；真正的下一节点由
     * 主 Graph 对 {@code resultType}/{@code rerouteIntent} 做白名单映射后决定。附加的证据、工具引用
     * 和确认令牌用于保持现有 API 能力，但不会扩大领域 Agent 的路由权限。
     */
    public record SubGraphResult(NodeResultType resultType, Intent rerouteIntent,
                                 String responseDraft, String clarificationQuestion,
                                 String businessTaskId, String failureReason,
                                 ExecutionStatus executionStatus,
                                 List<String> evidence, List<String> toolResults,
                                 String confirmToken, boolean multiCandidate,
                                 String selectedCandidateId) {
        public SubGraphResult {
            resultType = resultType == null ? NodeResultType.RETRYABLE_FAILURE : resultType;
            rerouteIntent = rerouteIntent == null ? Intent.UNKNOWN : rerouteIntent;
            responseDraft = responseDraft == null ? "" : responseDraft;
            clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
            failureReason = failureReason == null ? "" : failureReason;
            executionStatus = executionStatus == null ? ExecutionStatus.FAILED : executionStatus;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        }

        public SubGraphResult(NodeResultType resultType, Intent rerouteIntent,
                              String responseDraft, String clarificationQuestion,
                              String businessTaskId, String failureReason) {
            this(resultType, rerouteIntent, responseDraft, clarificationQuestion, businessTaskId,
                    failureReason, ExecutionStatus.COMPLETED, List.of(), List.of(), null, false, null);
        }
    }

    /**
     * 一次 Run 内冻结的租户配置版本。长流程恢复时沿用这些版本，或由恢复器显式迁移并重新校验；
     * 不能在等待用户确认期间悄悄切换补偿规则、工具 Schema 或路由阈值。
     */
    public record TenantAgentConfigSnapshot(String tenantConfigVersion, String promptVersion,
                                            String policyVersion, String knowledgeBaseVersion,
                                            String toolSchemaVersion, String routingConfigVersion,
                                            String topologyVersion, List<String> enabledTools,
                                            String customerServiceQueue) {
        public TenantAgentConfigSnapshot {
            tenantConfigVersion = version(tenantConfigVersion, "tenant-config-v1");
            promptVersion = version(promptVersion, "prompt-v1");
            policyVersion = version(policyVersion, "policy-v1");
            knowledgeBaseVersion = version(knowledgeBaseVersion, "knowledge-v1");
            toolSchemaVersion = version(toolSchemaVersion, "tool-schema-v1");
            routingConfigVersion = version(routingConfigVersion, "routing-v1");
            topologyVersion = version(topologyVersion, "topology-v1");
            enabledTools = enabledTools == null ? List.of() : List.copyOf(enabledTools);
            customerServiceQueue = version(customerServiceQueue, "DEFAULT");
        }

        private static String version(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.strip();
        }
    }

    public record ChatRequest(String tenantId, String userId,
                              @Size(max = 120) String conversationId,
                              @Size(max = 120) String messageId,
                              @NotBlank @Size(max = 4000) String content) {}

    public record ChatResponse(String runId, String subRunId, String traceId, Intent intent,
                               double confidence, RiskLevel riskLevel, String answer,
                               List<String> evidence, List<String> toolResults, ExecutionStatus status,
                               String businessTaskId, String confirmToken, boolean multiCandidate,
                               String selectedCandidateId, long elapsedMs) {}

    public record ConfirmRequest(@NotBlank @Size(max = 2048) String confirmToken, boolean confirmed) {}
    public record ConfirmResponse(String businessTaskId, String status, String message, String traceId,
                                  String supportCaseId) {}

    public record RefundEvidenceDescriptor(String mediaType, String contentType,
                                           String originalFilename, long sizeBytes) {}
    public record RefundReasonAssessmentRequest(String tenantId, String orderId, String productName,
                                                 String orderStatus, String paymentStatus, String logisticsStatus,
                                                 String reason,
                                                 List<KnowledgeDoc> rules,
                                                 List<RefundEvidenceDescriptor> evidence) {}
    public record RefundReasonScore(int score, boolean policyEligible, String summary,
                                    List<String> matchedRuleIds, List<String> missingInformation) {}
    public record RefundEvidenceView(String id, String mediaType, String contentType,
                                     String originalFilename, long sizeBytes, String downloadUrl) {}
    public record RefundAssessmentView(String businessTaskId, String orderId, String reason,
                                       int score, boolean policyEligible, String decisionMode,
                                       String summary, List<String> matchedRuleIds,
                                       List<String> missingInformation, String ruleVersion,
                                       String modelVersion, List<RefundEvidenceView> evidence,
                                       Instant createdAt) {}
    public record RefundRequestResponse(String taskId, String confirmToken, String status,
                                        String message, RefundAssessmentView assessment) {}

    public record TenantView(String tenantId, String tenantCode, String displayName,
                             String status, String tenantType, String storeId) {}
    public record TenantProvisionRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{2,31}") String tenantCode,
            @NotBlank @Size(max = 120) String tenantName,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") String adminUsername,
            @NotBlank @Size(min = 8, max = 128) String adminPassword,
            @NotBlank @Size(max = 40) String adminDisplayName,
            @NotBlank @Size(min = 8, max = 256) String storeAgentInviteCode,
            @NotBlank @Size(max = 256) String provisioningKey) {}

    public record CustomerRegisterRequest(
                                          @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") String username,
                                          @NotBlank @Size(min = 8, max = 128) String password,
                                          @NotBlank @Size(max = 40) String displayName) {}
    public record StaffRegisterRequest(
                                       @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{2,31}") String tenantCode,
                                       @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") String username,
                                       @NotBlank @Size(min = 8, max = 128) String password,
                                       @NotBlank @Size(max = 40) String displayName,
                                       @NotNull AccountRole role, @Size(max = 40) String storeId,
                                       @NotBlank @Size(max = 256) String inviteCode) {}
    public record LoginRequest(
                               @Size(max = 32) String tenantCode,
                               @NotBlank @Size(max = 32) String username,
                               @NotBlank @Size(max = 128) String password) {}
    public record AccountView(String id, String username, String displayName, AccountRole role,
                              String tenantId, String storeId) {}
    public record AuthResponse(String token, Instant expiresAt, AccountView account) {}

    public record SupportCaseView(String id, String tenantId, String type, String queueName, String status,
                                  String riskLevel, String summary, String customerId, String storeId,
                                  String conversationId, String businessTaskId, String orderId, String assigneeId,
                                  Instant createdAt, Instant updatedAt) {}
    public record SupportMessageView(String id, String caseId, String senderId, String senderRole,
                                     String content, Instant createdAt) {}
    public record SupportMessageRequest(@NotBlank @Size(max = 2000) String content) {}
    public record StoreContactRequest(@NotBlank @Size(max = 40) String productId,
                                      @Size(max = 2000) String message) {}
    public record OrderSelectionRequest(@NotBlank @Size(max = 40) String orderId) {}
    public record StaffDecisionRequest(@NotBlank @Size(max = 40) String decision,
                                       @Size(max = 1000) String comment) {}
    public record KnowledgeDecisionRequest(@NotBlank @Size(max = 40) String decision,
                                           @Size(max = 1000) String comment) {}
    public record KnowledgeCandidateView(String id, String tenantId, String normalizedQuestion, String proposedAnswer,
                                         Intent intent, List<String> evidence, int judgeScore,
                                         String sourceTraceId, String status, String rejectReason,
                                         Instant createdAt) {}
    public record MemoryCandidateView(String id, String factKey, String factValue, String memoryType,
                                      double confidence, boolean explicitlyConfirmed, int ttlDays,
                                      String status, String rejectReason, Instant createdAt) {}
    public record MemoryDecisionRequest(@NotBlank @Size(max = 40) String decision,
                                        @Size(max = 1000) String comment) {}
    public record ProfileCorrectionRequest(@NotBlank @Size(max = 80) String value,
                                           @jakarta.validation.constraints.Min(7)
                                           @jakarta.validation.constraints.Max(365) int ttlDays) {}
    public record UserProfileView(String attributeCode, String value, String trustLevel,
                                  long version, Instant expiresAt, Instant updatedAt) {}
}
