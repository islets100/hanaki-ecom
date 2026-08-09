package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.ModelMemoryFact;
import com.hanaki.ecom.domain.Domain.MemoryCandidateView;
import com.hanaki.ecom.domain.Domain.MemoryDecisionRequest;
import com.hanaki.ecom.domain.Domain.ProfileCorrectionRequest;
import com.hanaki.ecom.domain.Domain.UserProfileView;
import com.hanaki.ecom.memory.api.MemoryContextQuery;
import com.hanaki.ecom.memory.api.MemoryContextResult;
import com.hanaki.ecom.memory.application.MemoryContextBuilder;
import com.hanaki.ecom.memory.application.MemoryPolicyEngine;
import com.hanaki.ecom.memory.domain.ConversationSummaryDocument;
import com.hanaki.ecom.memory.domain.MemoryLayer;
import com.hanaki.ecom.memory.domain.MemoryLoadPhase;
import com.hanaki.ecom.memory.domain.MemoryScope;
import com.hanaki.ecom.memory.domain.MemorySegment;
import com.hanaki.ecom.memory.domain.MemoryTrustLevel;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import com.hanaki.ecom.store.EcommerceStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 四层 Memory 的应用层门面。
 *
 * <p>职责边界如下：</p>
 * <ul>
 *   <li>工作记忆：由 Graph State 保存，本服务不把完整对象或历史消息复制进 State。</li>
 *   <li>短期记忆：MySQL 保存完整审计消息，Redis 仅缓存热窗口；摘要使用消息序号和数据库 CAS。</li>
 *   <li>情景记忆：MySQL 是事实源，Elasticsearch 是带租户/用户/领域/TTL 过滤的检索投影。</li>
 *   <li>长期画像：只读取字段目录允许且已由用户确认的当前版本，旧版本仅留在审计历史。</li>
 * </ul>
 *
 * <p>Agent 不直接访问任何 Memory 存储。所有候选最终都交给 MemoryContextBuilder 做二次作用域、
 * 策略和 Token 预算检查；外部依赖失败时返回降级上下文，不以历史记忆代替订单/退款真实状态。</p>
 */
@Service
public class MemoryContextService {
    private static final String MEMORY_PROMPT_VERSION = "memory-summary-v3";
    private static final TypeReference<List<MemoryPersistenceStore.StoredMessage>> MESSAGE_LIST = new TypeReference<>() {};

    private final JdbcClient db;
    private final EmbeddingModel embeddings;
    private final MemoryModelService memoryModel;
    private final ObjectMapper json;
    private final String embeddingModelName;
    private final AgentTelemetryService telemetry;
    private final MemoryPersistenceStore persistence;
    private final ElasticsearchMemoryIndex memoryIndex;
    private final RedisRespClient redis;
    private final TokenBudgetEstimator tokens;
    private final MemoryPolicyEngine policy;
    private final MemoryContextBuilder contextBuilder;

    private final int recentMessages;
    private final int mainRecentMessages;
    private final int maxWindowTokens;
    private final int maxMessageCount;
    private final int summarizeBatchSize;
    private final int retainUnsummarizedMessages;
    private final int maxTotalMemoryTokens;
    private final int maxRecentMessageTokens;
    private final int maxSummaryTokens;
    private final int maxEpisodicTokens;
    private final int maxProfileTokens;
    private final int maxSingleMemoryTokens;
    private final Duration messageWindowTtl;
    private final Duration summaryLeaseTtl;

    private final Counter summaryConflict;
    private final Counter injectionRejected;
    private final Counter degradation;

    @Autowired
    public MemoryContextService(
            EcommerceStore ignoredStore,
            JdbcClient db,
            EmbeddingModel embeddings,
            MemoryModelService memoryModel,
            ObjectMapper json,
            AgentTelemetryService telemetry,
            MemoryPersistenceStore persistence,
            ElasticsearchMemoryIndex memoryIndex,
            RedisRespClient redis,
            TokenBudgetEstimator tokens,
            MemoryPolicyEngine policy,
            MemoryContextBuilder contextBuilder,
            MeterRegistry meters,
            @Value("${agent.memory.conversation.recent-messages:8}") int recentMessages,
            @Value("${agent.memory.conversation.main-recent-messages:4}") int mainRecentMessages,
            @Value("${agent.memory.conversation.max-window-tokens:6000}") int maxWindowTokens,
            @Value("${agent.memory.conversation.max-message-count:20}") int maxMessageCount,
            @Value("${agent.memory.conversation.summarize-batch-size:10}") int summarizeBatchSize,
            @Value("${agent.memory.conversation.retain-unsummarized-messages:8}") int retainUnsummarizedMessages,
            @Value("${agent.memory.context.max-total-memory-tokens:2000}") int maxTotalMemoryTokens,
            @Value("${agent.memory.context.max-recent-message-tokens:900}") int maxRecentMessageTokens,
            @Value("${agent.memory.context.max-summary-tokens:450}") int maxSummaryTokens,
            @Value("${agent.memory.context.max-episodic-tokens:450}") int maxEpisodicTokens,
            @Value("${agent.memory.context.max-profile-tokens:250}") int maxProfileTokens,
            @Value("${agent.memory.context.max-single-memory-tokens:240}") int maxSingleMemoryTokens,
            @Value("${agent.memory.conversation.hot-window-ttl-days:3}") int hotWindowTtlDays,
            @Value("${agent.memory.conversation.summary-lease-seconds:30}") int summaryLeaseSeconds,
            @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v3}") String embeddingModelName) {
        this.db = db;
        this.embeddings = embeddings;
        this.memoryModel = memoryModel;
        this.json = json;
        this.telemetry = telemetry;
        this.persistence = persistence;
        this.memoryIndex = memoryIndex;
        this.redis = redis;
        this.tokens = tokens;
        this.policy = policy;
        this.contextBuilder = contextBuilder;
        this.recentMessages = Math.max(2, recentMessages);
        this.mainRecentMessages = Math.max(2, Math.min(mainRecentMessages, this.recentMessages));
        this.maxWindowTokens = Math.max(128, maxWindowTokens);
        this.maxMessageCount = Math.max(4, maxMessageCount);
        this.summarizeBatchSize = Math.max(2, summarizeBatchSize);
        this.retainUnsummarizedMessages = Math.max(2, retainUnsummarizedMessages);
        this.maxTotalMemoryTokens = Math.max(128, maxTotalMemoryTokens);
        this.maxRecentMessageTokens = Math.max(0, maxRecentMessageTokens);
        this.maxSummaryTokens = Math.max(0, maxSummaryTokens);
        this.maxEpisodicTokens = Math.max(0, maxEpisodicTokens);
        this.maxProfileTokens = Math.max(0, maxProfileTokens);
        this.maxSingleMemoryTokens = Math.max(32, maxSingleMemoryTokens);
        this.messageWindowTtl = Duration.ofDays(Math.max(1, hotWindowTtlDays));
        this.summaryLeaseTtl = Duration.ofSeconds(Math.max(5, summaryLeaseSeconds));
        this.embeddingModelName = embeddingModelName;
        this.summaryConflict = meters.counter("memory.summary.conflict");
        this.injectionRejected = meters.counter("memory.injection.rejected");
        this.degradation = meters.counter("memory.degradation");
    }

    /**
     * 保留旧测试和迁移中调用方的构造器。生产 Spring Bean 始终走上面的完整构造器。
     */
    MemoryContextService(EcommerceStore ignoredStore, JdbcClient db, EmbeddingModel embeddings,
                         MemoryModelService memoryModel, ObjectMapper json,
                         AgentTelemetryService telemetry, MemoryPersistenceStore persistence,
                         ElasticsearchMemoryIndex memoryIndex, int recentTurns, int ignoredMaxChars,
                         String embeddingModelName) {
        this.db = db;
        this.embeddings = embeddings;
        this.memoryModel = memoryModel;
        this.json = json;
        this.telemetry = telemetry;
        this.persistence = persistence;
        this.memoryIndex = memoryIndex;
        this.redis = null;
        this.tokens = new TokenBudgetEstimator();
        this.policy = new MemoryPolicyEngine();
        this.contextBuilder = new MemoryContextBuilder(this.tokens, this.policy);
        this.recentMessages = Math.max(2, recentTurns);
        this.mainRecentMessages = Math.min(4, this.recentMessages);
        this.maxWindowTokens = 6000;
        this.maxMessageCount = 20;
        this.summarizeBatchSize = 10;
        this.retainUnsummarizedMessages = 8;
        this.maxTotalMemoryTokens = Math.max(256, ignoredMaxChars / 2);
        this.maxRecentMessageTokens = Math.max(128, this.maxTotalMemoryTokens / 2);
        this.maxSummaryTokens = 450;
        this.maxEpisodicTokens = 450;
        this.maxProfileTokens = 250;
        this.maxSingleMemoryTokens = 240;
        this.messageWindowTtl = Duration.ofDays(3);
        this.summaryLeaseTtl = Duration.ofSeconds(30);
        this.embeddingModelName = embeddingModelName;
        MeterRegistry meters = new SimpleMeterRegistry();
        this.summaryConflict = meters.counter("memory.summary.conflict");
        this.injectionRejected = meters.counter("memory.injection.rejected");
        this.degradation = meters.counter("memory.degradation");
    }

    /**
     * 旧 API 现在明确等价于 MAIN 阶段：只返回摘要和少量最近消息，不再在路由之前执行 Embedding
     * 或加载完整画像。业务 Agent 必须调用 buildBusinessContext 才能按领域加载后三层数据。
     */
    public List<String> build(String tenantId, String userId, String conversationId, String currentMessage) {
        MemoryScope scope = MemoryScope.conversation(tenantId, userId, conversationId, "", "", "INTENT_ROUTE");
        return buildMainContext(scope, currentMessage, "").legacyLines();
    }

    public MemoryContextResult buildMainContext(MemoryScope scope, String currentMessage, String traceId) {
        MemoryContextQuery query = new MemoryContextQuery(scope, MemoryLoadPhase.MAIN, currentMessage,
                Math.min(800, maxTotalMemoryTokens), Math.min(500, maxRecentMessageTokens),
                Math.min(300, maxSummaryTokens), 0, 0, maxSingleMemoryTokens);
        return buildContext(query, traceId);
    }

    public MemoryContextResult buildBusinessContext(MemoryScope scope, String currentMessage, String traceId) {
        if ("MAIN".equals(scope.agentType()))
            throw new IllegalArgumentException("业务阶段必须提供具体 agentType");
        MemoryContextQuery query = new MemoryContextQuery(scope, MemoryLoadPhase.BUSINESS, currentMessage,
                maxTotalMemoryTokens, maxRecentMessageTokens, maxSummaryTokens,
                maxEpisodicTokens, maxProfileTokens, maxSingleMemoryTokens);
        return buildContext(query, traceId);
    }

    private MemoryContextResult buildContext(MemoryContextQuery query, String traceId) {
        List<MemorySegment> candidates = new ArrayList<>();
        List<String> degradations = new ArrayList<>();
        loadSummary(query.scope(), candidates, degradations);
        // 任务索引与滚动摘要同属短期会话记忆。它只告诉 Agent “曾经创建过哪个业务任务、当前处于
        // 什么执行阶段”，绝不携带订单、退款、账户等业务对象快照；真正恢复流程时仍须用
        // businessTaskId 调用权威业务接口。这一层在路由阶段也可见，能帮助 Main Agent 把用户的
        // “继续刚才那个退款”路由到正确领域，但不会提前加载画像或执行向量检索。
        loadConversationTasks(query.scope(), candidates, degradations);
        loadRecentWindow(query.scope(), query.phase() == MemoryLoadPhase.MAIN ? mainRecentMessages : recentMessages,
                candidates, degradations);
        if (query.phase() == MemoryLoadPhase.BUSINESS) {
            candidates.addAll(recallEpisodes(query.scope(), query.currentMessage(), degradations));
            loadProfiles(query.scope(), candidates, degradations);
        }
        MemoryContextResult result = contextBuilder.build(query, candidates, degradations);
        try {
            persistence.saveAccessAudit(query.scope(), traceId, result.manifest());
        } catch (RuntimeException auditUnavailable) {
            // 审计故障必须进入低基数指标，但 Memory 属于增强能力，不能因此阻断订单/退款主流程。
            degradation.increment();
            List<String> reasons = new ArrayList<>(result.degradationReasons());
            reasons.add("memory_access_audit_unavailable");
            return new MemoryContextResult(result.segments(), result.manifest(), result.estimatedTokens(),
                    result.truncated(), reasons.stream().distinct().toList());
        }
        return result;
    }

    private void loadSummary(MemoryScope scope, List<MemorySegment> target, List<String> degradations) {
        try {
            persistence.loadSummary(scope).ifPresent(summary -> target.add(new MemorySegment(
                    "summary:" + scope.conversationId(), scope.tenantId(), scope.userId(), MemoryLayer.CONVERSATION,
                    "会话摘要（version=" + summary.version() + ",covered=" + summary.coveredStartSeq() + "-" +
                            summary.coveredEndSeq() + "，低优先级）：" + summary.summaryJson(),
                    "CONVERSATION_SUMMARY", MemoryTrustLevel.MODEL_EXTRACTED,
                    Long.toString(summary.version()), 0.85, 0.75, summary.updatedAt(),
                    summary.updatedAt().plus(30, ChronoUnit.DAYS), 0)));
        } catch (RuntimeException unavailable) {
            degradations.add("summary_store_unavailable");
            degradation.increment();
        }
    }

    /**
     * 将数据库中的业务任务引用映射成受统一预算控制的会话记忆。
     *
     * <p>这里有意只渲染 taskId、agentType、状态和待办类型四个字段。即使历史任务仍在有效期内，
     * 这段文本也明确要求模型重新查询权威业务系统，防止把 Memory 中的旧状态当成退款/订单事实。
     * 读取失败只关闭“继续历史任务”的增强能力，不阻断当前问题。</p>
     */
    private void loadConversationTasks(MemoryScope scope, List<MemorySegment> target,
                                       List<String> degradations) {
        try {
            for (MemoryPersistenceStore.StoredConversationTask task : persistence.loadConversationTasks(scope)) {
                String pending = task.pendingActionType() == null || task.pendingActionType().isBlank()
                        ? "无" : task.pendingActionType();
                target.add(new MemorySegment(
                        "conversation-task:" + task.businessTaskId(), scope.tenantId(), scope.userId(),
                        MemoryLayer.CONVERSATION,
                        "会话摘要（任务索引，version=" + task.version() + "）：businessTaskId="
                                + task.businessTaskId() + "；agentType=" + task.agentType()
                                + "；status=" + task.taskStatus() + "；pendingAction=" + pending
                                + "。该记录仅用于定位任务；继续处理前必须查询业务系统的最新真实状态。",
                        "CONVERSATION_TASK_INDEX", MemoryTrustLevel.BUSINESS_VERIFIED,
                        Long.toString(task.version()), 1.0, 1.0, task.updatedAt(), task.expiresAt(), 0));
            }
        } catch (RuntimeException unavailable) {
            degradations.add("conversation_task_index_unavailable");
            degradation.increment();
        }
    }

    private void loadRecentWindow(MemoryScope scope, int limit, List<MemorySegment> target,
                                  List<String> degradations) {
        List<MemoryPersistenceStore.StoredMessage> messages = null;
        String cacheKey = scope.conversationKey("msg-window");
        if (redis != null && redis.enabled()) {
            try {
                messages = redis.get(cacheKey).map(value -> decodeMessages(value, limit)).orElse(null);
            } catch (RuntimeException unavailable) {
                degradations.add("redis_window_unavailable_mysql_fallback");
                degradation.increment();
            }
        }
        if (messages == null) {
            try {
                messages = persistence.loadRecentMessages(scope, limit);
                cacheMessages(cacheKey, messages);
            } catch (RuntimeException unavailable) {
                degradations.add("message_store_unavailable_current_question_only");
                degradation.increment();
                messages = List.of();
            }
        }
        for (MemoryPersistenceStore.StoredMessage message : messages) {
            target.add(new MemorySegment("message:" + message.id(), scope.tenantId(), scope.userId(),
                    MemoryLayer.CONVERSATION, message.role() + ": " + redact(message.content()),
                    "CONVERSATION_MESSAGE", message.trustLevel(), Long.toString(message.seq()),
                    1.0, 1.0, message.createdAt(), message.createdAt().plus(14, ChronoUnit.DAYS),
                    message.tokenCount()));
        }
    }

    private void loadProfiles(MemoryScope scope, List<MemorySegment> target, List<String> degradations) {
        try {
            Set<String> allowed = policy.allowedProfileAttributes(scope.agentType());
            for (MemoryPersistenceStore.StoredProfile profile : persistence.loadProfiles(scope, allowed)) {
                target.add(new MemorySegment("profile:" + profile.attributeCode(), scope.tenantId(), scope.userId(),
                        MemoryLayer.PROFILE,
                        "已确认用户画像：<memory type=\"preference\" trust=\"" + profile.trustLevel().name()
                                + "\">" + profile.attributeCode() + "：" + profile.value() + "</memory>",
                        profile.sourceType(), profile.trustLevel(), Long.toString(profile.version()),
                        0.75, profile.confidence(), profile.updatedAt(), profile.expiresAt(), 0));
            }
        } catch (RuntimeException unavailable) {
            degradations.add("profile_store_unavailable_personalization_disabled");
            degradation.increment();
        }
    }

    /** 用户只能查看自己的记忆候选；默认同时展示待确认、已同意和已拒绝，便于审计。 */
    public List<MemoryCandidateView> candidates(SessionAccount customer, String status) {
        String normalized = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        if (!normalized.isBlank() && !Set.of("PENDING", "APPROVED", "REJECTED").contains(normalized))
            throw new IllegalArgumentException("status 只能是 PENDING、APPROVED 或 REJECTED");
        String sql = "select * from memory_candidate where tenant_id=:tenant and user_id=:user " +
                (normalized.isBlank() ? "" : "and status=:status ") + "order by created_at desc limit 100";
        var query = db.sql(sql).param("tenant", customer.tenantId()).param("user", customer.id());
        if (!normalized.isBlank()) query = query.param("status", normalized);
        return query.query((rs, row) -> new MemoryCandidateView(rs.getString("id"), rs.getString("fact_key"),
                rs.getString("fact_value"), rs.getString("memory_type"), rs.getDouble("confidence"),
                rs.getBoolean("explicitly_confirmed"), rs.getInt("ttl_days"), rs.getString("status"),
                rs.getString("reject_reason"), rs.getTimestamp("created_at").toInstant())).list();
    }

    /** 用户画像管理页读取的是本人全部当前字段，不等同于给某个 Agent 的 Prompt 投影。 */
    public List<UserProfileView> profiles(SessionAccount customer) {
        MemoryScope scope = MemoryScope.conversation(customer.tenantId(), customer.id(),
                "profile-administration", "", "", "PROFILE_ADMIN");
        return persistence.loadProfiles(scope, policy.allProfileAttributes()).stream()
                .map(value -> new UserProfileView(value.attributeCode(), value.value(),
                        value.trustLevel().name(), value.version(), value.expiresAt(), value.updatedAt()))
                .toList();
    }

    /**
     * 用户主动更正比历史画像优先。数据库当前值、历史 SUPERSEDED 版本和 Outbox 在同一事务提交；
     * ES 删除旧投影、生成新向量在事务外重试，避免远程调用占住用户行锁。
     */
    @Transactional
    public UserProfileView correctProfile(String attributeCode, ProfileCorrectionRequest request,
                                          SessionAccount customer) {
        MemoryPolicyEngine.PolicyDecision decision = policy.validateProfileCandidate(attributeCode,
                request.value(), "PREFERENCE", 1.0);
        if (!decision.allowed()) throw new IllegalArgumentException("画像更正不符合策略：" + decision.reason());
        var previous = persistence.currentProfile(customer.tenantId(), customer.id(), attributeCode);
        int ttl = policy.clampProfileTtl(attributeCode, request.ttlDays());
        String runId = "profile-" + UUID.randomUUID();
        persistence.upsertProfileFact(customer.tenantId(), customer.id(), attributeCode,
                request.value().strip(), runId, Instant.now().plus(ttl, ChronoUnit.DAYS));
        previous.filter(value -> !value.value().equals(request.value().strip())).ifPresent(value ->
                enqueueProfileProjectionEvent(customer.tenantId(), customer.id(), runId,
                        "MemoryProfileProjectionDeleted", attributeCode, value.value(), ttl));
        enqueueProfileProjectionEvent(customer.tenantId(), customer.id(), runId,
                "MemoryProfileProjectionChanged", attributeCode, request.value().strip(), ttl);
        return profiles(customer).stream().filter(value -> value.attributeCode().equals(attributeCode))
                .findFirst().orElseThrow();
    }

    /** 删除接口幂等；数据库立即禁止召回，ES 删除投影由 Outbox 至少一次重试。 */
    @Transactional
    public boolean deleteProfile(String attributeCode, SessionAccount customer) {
        if (!policy.registeredProfileAttribute(attributeCode))
            throw new IllegalArgumentException("未注册的画像属性：" + attributeCode);
        var deleted = persistence.deleteProfileFact(customer.tenantId(), customer.id(), attributeCode);
        deleted.ifPresent(value -> enqueueProfileProjectionEvent(customer.tenantId(), customer.id(),
                "profile-delete-" + UUID.randomUUID(), "MemoryProfileProjectionDeleted",
                value.attributeCode(), value.value(), 30));
        return deleted.isPresent();
    }

    /**
     * 用户审批是画像从 CANDIDATE 进入 CONFIRMED 的可信边界。事务内锁用户行、更新候选、覆盖当前
     * 画像并写 Outbox；Embedding/ES 激活由 Worker 在事务外完成。
     */
    @Transactional
    public MemoryCandidateView decide(String id, MemoryDecisionRequest request, SessionAccount customer) {
        db.sql("select id from app_account where id=:user and " +
                        "(tenant_id=:tenant or (tenant_id='platform' and role='CUSTOMER')) for update")
                .param("tenant", customer.tenantId()).param("user", customer.id())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("当前用户不存在或不属于该租户"));
        MemoryCandidateRow candidate = findCandidate(id, customer.tenantId(), customer.id());
        if (!"PENDING".equals(candidate.status())) throw new IllegalArgumentException("该记忆候选已处理");
        String decision = request.decision() == null ? "" : request.decision().strip().toUpperCase(Locale.ROOT);
        if ("REJECT".equals(decision)) {
            String reason = request.comment() == null || request.comment().isBlank()
                    ? "用户拒绝记忆" : request.comment().strip();
            db.sql("update memory_candidate set status='REJECTED',reject_reason=:reason,reviewed_at=current_timestamp " +
                            "where id=:id and tenant_id=:tenant and user_id=:user and status='PENDING'")
                    .param("reason", reason.substring(0, Math.min(255, reason.length()))).param("id", id)
                    .param("tenant", customer.tenantId()).param("user", customer.id()).update();
        } else if ("APPROVE".equals(decision)) {
            var previousProfile = persistence.currentProfile(
                    customer.tenantId(), customer.id(), candidate.factKey());
            db.sql("update memory_candidate set status='APPROVED',reject_reason=null,explicitly_confirmed=true," +
                            "trust_level='USER_CONFIRMED',reviewed_at=current_timestamp " +
                            "where id=:id and tenant_id=:tenant and user_id=:user and status='PENDING'")
                    .param("id", id).param("tenant", customer.tenantId()).param("user", customer.id()).update();
            ModelMemoryFact fact = new ModelMemoryFact(candidate.factKey(), candidate.factValue(),
                    candidate.memoryType(), true, candidate.confidence(), candidate.ttlDays());
            approveProfileFact(customer.tenantId(), customer.id(), candidate.sourceRunId(), fact);
            previousProfile.filter(value -> !value.value().equals(candidate.factValue())).ifPresent(value ->
                    enqueueProfileProjectionEvent(customer.tenantId(), customer.id(), candidate.sourceRunId(),
                            "MemoryProfileProjectionDeleted", candidate.factKey(), value.value(), candidate.ttlDays()));
            enqueueMemoryActivation(customer.tenantId(), id, candidate.sourceRunId());
        } else throw new IllegalArgumentException("decision 只能是 APPROVE 或 REJECT");
        return findCandidate(id, customer.tenantId(), customer.id()).view();
    }

    /**
     * 回答提交后异步维护 Memory。摘要和候选提取都可失败/延迟，不改变已经提交的客服响应。
     */
    @Async
    public void update(String tenantId, String userId, String conversationId, String runId, String traceId,
                       String userMessage, String assistantAnswer) {
        MemoryScope scope = MemoryScope.conversation(tenantId, userId, conversationId, runId,
                "sub-" + safeId(runId), "SUMMARY_WORKER");
        telemetry.observeTrace(traceId, "memory.update", "MEMORY",
                Map.of("conversation", telemetry.scopedKey(conversationId),
                        "messageLength", userMessage == null ? 0 : userMessage.length()),
                Map.of("async", true, "identitySource", "server-context"), () -> {
                    refreshWindowCache(scope);
                    boolean summaryUpdated = updateSummaryIfNeeded(scope);
                    extractCandidates(scope, userMessage);
                    return Map.of("summaryUpdated", summaryUpdated);
                });
    }

    /**
     * 同步保存当前会话与业务任务的关联。
     *
     * <p>它必须在业务回答提交前调用：若任务已经在订单/售后系统创建，却只依赖异步摘要来记录，
     * Worker 延迟或宕机会让下一轮对话无法找到该任务。这里仍然只写一个很小的索引，不复制业务
     * 详情。等待用户/人工动作的任务保留七天，已结束任务保留三十天，便于在近期追问中定位。</p>
     */
    public void recordConversationTask(MemoryScope scope, String executionStatus) {
        if (scope.businessTaskId().isBlank()) return;
        String status = executionStatus == null ? "UNKNOWN" : executionStatus.strip().toUpperCase(Locale.ROOT);
        String pendingAction = switch (status) {
            case "NEED_CLARIFICATION" -> "USER_CLARIFICATION";
            case "WAITING_CONFIRMATION" -> "USER_CONFIRMATION";
            case "WAITING_STAFF_APPROVAL" -> "STAFF_APPROVAL";
            case "HANDOFF" -> "HUMAN_SERVICE";
            default -> "";
        };
        boolean waiting = !pendingAction.isBlank() || "BLOCKED".equals(status);
        Instant expiresAt = Instant.now().plus(waiting ? 7 : 30, ChronoUnit.DAYS);
        persistence.upsertConversationTask(scope, status, pendingAction, expiresAt);
    }

    private boolean updateSummaryIfNeeded(MemoryScope scope) {
        MemoryPersistenceStore.ConversationWindowStats stats = persistence.conversationWindowStats(scope);
        if (stats.messageCount() <= maxMessageCount && stats.tokenCount() <= maxWindowTokens) return false;
        MemoryPersistenceStore.SummarySnapshot expected = persistence.loadSummary(scope)
                .orElse(MemoryPersistenceStore.SummarySnapshot.empty());
        String leaseKey = scope.conversationKey("summary-lease");
        String leaseOwner = UUID.randomUUID().toString();
        boolean leased = false;
        if (redis != null && redis.enabled()) {
            try {
                leased = redis.tryAcquireLease(leaseKey, leaseOwner, summaryLeaseTtl);
                if (!leased) return false; // 另一实例正在计算；最终正确性仍由下面的数据库 CAS 保证。
            } catch (RuntimeException unavailable) {
                // Redis 锁只减少重复模型调用；不可用时继续计算，不能把它当作正确性前提。
                degradation.increment();
            }
        }
        try {
            List<MemoryPersistenceStore.StoredMessage> uncovered = persistence.loadMessagesAfter(scope,
                    expected.coveredEndSeq(), summarizeBatchSize + retainUnsummarizedMessages);
            int compressible = Math.max(0, uncovered.size() - retainUnsummarizedMessages);
            int batchSize = Math.min(summarizeBatchSize, compressible);
            if (batchSize == 0) return false;
            List<MemoryPersistenceStore.StoredMessage> batch = List.copyOf(uncovered.subList(0, batchSize));
            long startSeq = expected.coveredStartSeq() > 0 ? expected.coveredStartSeq() : batch.getFirst().seq();
            long endSeq = batch.getLast().seq();
            String summaryText = summarizeWithFallback(expected.summaryJson(), batch);
            List<String> flags = batch.stream().anyMatch(value -> policy.containsPromptInjection(value.content()))
                    ? List.of("UNTRUSTED_INSTRUCTION_REMOVED") : List.of();
            ConversationSummaryDocument document = new ConversationSummaryDocument(
                    summaryText.isBlank() ? List.of() : List.of(summaryText), Map.of(), List.of(),
                    unresolvedQuestions(batch), List.of(), List.of(), flags, endSeq);
            String summaryJson = json.writeValueAsString(document);
            String sourceHash = sha256(batch.stream().map(value -> value.seq() + ":" + sha256(value.content()))
                    .reduce((left, right) -> left + "|" + right).orElse(""));
            boolean committed = persistence.compareAndSetSummary(scope, expected, summaryJson, startSeq, endSeq,
                    sourceHash, "memory-model", MEMORY_PROMPT_VERSION,
                    batch.stream().mapToInt(MemoryPersistenceStore.StoredMessage::tokenCount).sum(),
                    tokens.estimate(summaryJson));
            if (!committed) summaryConflict.increment();
            return committed;
        } catch (Exception error) {
            degradation.increment();
            return false;
        } finally {
            if (leased) {
                try { redis.releaseLease(leaseKey, leaseOwner); }
                catch (RuntimeException ignored) { degradation.increment(); }
            }
        }
    }

    private List<String> unresolvedQuestions(List<MemoryPersistenceStore.StoredMessage> batch) {
        return batch.stream().filter(value -> "USER".equalsIgnoreCase(value.role()))
                .map(MemoryPersistenceStore.StoredMessage::content)
                .filter(value -> value.contains("?") || value.contains("？"))
                .map(this::redact).filter(value -> !policy.containsPromptInjection(value))
                .map(value -> value.substring(0, Math.min(120, value.length()))).limit(5).toList();
    }

    private String summarizeWithFallback(String previousSummary,
                                         List<MemoryPersistenceStore.StoredMessage> messages) {
        List<String> safeMessages = messages.stream()
                .map(value -> "seq=" + value.seq() + " role=" + value.role() + " content=" + redact(value.content()))
                .toList();
        try {
            var result = memoryModel.summarizeIncremental(previousSummary, safeMessages);
            if (result != null && result.summary() != null && !result.summary().isBlank()
                    && !policy.containsPromptInjection(result.summary())) return redact(result.summary()).strip();
        } catch (RuntimeException ignored) { /* 下面使用确定性降级，不保存异常文本。 */ }
        return deterministicSummary(safeMessages);
    }

    private String deterministicSummary(List<String> messages) {
        String joined = String.join(" | ", messages);
        // 降级摘要只保留批次末尾，不声称业务动作完成；字符上限仅用于模型不可用时的保守兜底。
        String value = joined.length() <= 600 ? joined : joined.substring(joined.length() - 600);
        return policy.containsPromptInjection(value) ? "历史消息含不可信指令，已从摘要中移除" : value;
    }

    private void extractCandidates(MemoryScope scope, String message) {
        if (policy.containsPromptInjection(message)) {
            injectionRejected.increment();
            return;
        }
        if (!isReusable(message)) return;
        List<ModelMemoryFact> facts;
        try {
            var extraction = memoryModel.extract(message);
            facts = extraction == null || extraction.facts() == null ? List.of() : extraction.facts();
        } catch (RuntimeException modelUnavailable) {
            facts = deterministicConfirmedFallback(message);
        }
        for (ModelMemoryFact fact : facts) {
            MemoryPolicyEngine.PolicyDecision decision = fact == null
                    ? new MemoryPolicyEngine.PolicyDecision(false, "NULL_FACT")
                    : policy.validateProfileCandidate(fact.factKey(), fact.factValue(), fact.memoryType(), fact.confidence());
            if (!decision.allowed()) {
                if ("PROMPT_INJECTION".equals(decision.reason())) injectionRejected.increment();
                continue;
            }
            int ttl = policy.clampProfileTtl(fact.factKey(), fact.ttlDays());
            persistence.saveExtractedCandidate(UUID.randomUUID().toString(), scope.tenantId(), scope.userId(),
                    scope.runId(), fact.factKey(), fact.factValue(), fact.confidence(),
                    fact.explicitlyConfirmed(), ttl, "PENDING", Instant.now().plus(ttl, ChronoUnit.DAYS), "{}");
        }
    }

    private List<MemorySegment> recallEpisodes(MemoryScope scope, String query, List<String> degradations) {
        if (query == null || query.isBlank()) return List.of();
        float[] queryVector;
        try { queryVector = embeddings.embed(query); }
        catch (RuntimeException unavailable) {
            degradations.add("embedding_unavailable_lexical_episode_fallback");
            degradation.increment();
            return lexicalRecall(scope, query);
        }
        if (memoryIndex.enabled()) {
            try {
                List<MemorySegment> indexed = memoryIndex.search(scope.tenantId(), scope.userId(), scope.agentType(),
                                queryVector, 3).stream()
                        .map(hit -> new MemorySegment("episode:" + hit.memoryId(), scope.tenantId(), scope.userId(),
                                MemoryLayer.EPISODIC, "相关情景记忆（仅作背景，实时状态须重新查询）：" + hit.content(),
                                hit.sourceType(), hit.trustLevel(), Long.toString(hit.version()), hit.score(),
                                hit.confidence(), hit.occurredAt(), hit.promptEligibleUntil(), 0)).toList();
                if (!indexed.isEmpty()) return indexed;
            } catch (ElasticsearchMemoryIndex.MemoryIndexUnavailableException unavailable) {
                degradations.add("elasticsearch_unavailable_database_vector_fallback");
                degradation.increment();
            }
        }
        return persistence.loadEpisodes(scope, 80).stream()
                .map(episode -> new ScoredEpisode(episode, memoryScore(query, queryVector, episode)))
                .filter(value -> value.score() >= 0.35)
                .sorted(Comparator.comparingDouble(ScoredEpisode::score).reversed()).limit(3)
                .map(value -> episodeSegment(scope, value.episode(), value.score(), false)).toList();
    }

    private List<MemorySegment> lexicalRecall(MemoryScope scope, String query) {
        List<String> terms = lexicalTokens(query);
        if (terms.isEmpty()) return List.of();
        return persistence.loadEpisodes(scope, 30).stream()
                .filter(value -> terms.stream().anyMatch(value.content().toLowerCase(Locale.ROOT)::contains))
                .limit(3).map(value -> episodeSegment(scope, value, 0.36, true)).toList();
    }

    private MemorySegment episodeSegment(MemoryScope scope, MemoryPersistenceStore.StoredEpisode episode,
                                         double score, boolean lexical) {
        return new MemorySegment("episode:" + episode.id(), scope.tenantId(), scope.userId(), MemoryLayer.EPISODIC,
                "相关情景记忆（" + (lexical ? "降级召回，" : "") + "仅作背景，实时状态须重新查询）："
                        + episode.content(), episode.sourceType(), episode.trustLevel(),
                Long.toString(episode.version()), score, episode.confidence(), episode.occurredAt(),
                episode.promptEligibleUntil(), 0);
    }

    private double memoryScore(String queryText, float[] query, MemoryPersistenceStore.StoredEpisode episode) {
        try {
            float[] vector = json.readValue(episode.embeddingJson(), new TypeReference<>() {});
            double semantic = cosine(query, vector);
            double entityMatch = lexicalOverlap(queryText, episode.content());
            long ageDays = Math.max(0, ChronoUnit.DAYS.between(episode.occurredAt(), Instant.now()));
            double freshness = Math.exp(-ageDays / 90d);
            return semantic * 0.40 + entityMatch * 0.20 + episode.importance() * 0.15
                    + freshness * 0.15 + episode.trustLevel().score() * 0.10;
        } catch (Exception invalid) { return 0; }
    }

    private double lexicalOverlap(String query, String content) {
        List<String> terms = lexicalTokens(query);
        if (terms.isEmpty()) return 0;
        long matches = terms.stream().filter(content.toLowerCase(Locale.ROOT)::contains).count();
        return (double) matches / terms.size();
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, left = 0, right = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; left += a[i] * a[i]; right += b[i] * b[i];
        }
        return left == 0 || right == 0 ? 0 : dot / Math.sqrt(left * right);
    }

    private void saveEpisode(String tenantId, String userId, String runId, String content,
                             double importance, int ttlDays) {
        String normalized = redact(content).strip();
        try {
            float[] vector = embeddings.embed(normalized);
            String contentHash = sha256(normalized);
            Instant createdAt = Instant.now();
            Instant expiresAt = createdAt.plus(ttlDays, ChronoUnit.DAYS);
            double normalizedImportance = Math.max(0d, Math.min(1d, importance));
            persistence.saveEpisodeIfAbsent(tenantId, userId, runId, normalized, contentHash,
                    json.writeValueAsString(vector), embeddingModelName, normalizedImportance, expiresAt);
            memoryIndex.index(tenantId, userId, "PRE_SALE", contentHash, normalized, vector,
                    normalizedImportance, MemoryTrustLevel.USER_CONFIRMED, "USER_CONFIRMATION",
                    1, createdAt, expiresAt);
        } catch (Exception error) {
            throw new IllegalStateException("无法保存向量记忆", error);
        }
    }

    /** Outbox Worker 调用；状态和归属再次校验，保证重复投递安全。 */
    public void activateApprovedCandidate(String candidateId) {
        MemoryCandidateRow candidate = db.sql("select * from memory_candidate where id=:id and status='APPROVED'")
                .param("id", candidateId).query((rs, row) -> memoryCandidateRow(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("待激活记忆候选不存在或状态无效"));
        saveEpisode(candidate.tenantId(), candidate.userId(), candidate.sourceRunId(),
                candidate.factKey() + "：" + candidate.factValue(), candidate.confidence(), candidate.ttlDays());
    }

    /** Outbox Worker 调用：解析服务端生成的固定 Schema 后创建新的画像情景投影。 */
    public void activateProfileProjection(String payloadJson) {
        try {
            var payload = json.readTree(payloadJson);
            saveEpisode(required(payload, "tenantId"), required(payload, "userId"),
                    required(payload, "sourceRunId"), required(payload, "attributeCode") + "：" +
                            required(payload, "value"), 1.0, payload.path("ttlDays").asInt(90));
        } catch (Exception error) { throw new IllegalArgumentException("画像投影 Outbox payload 无效", error); }
    }

    /** Outbox Worker 调用：重复 DELETE 返回 404 也视为幂等成功。 */
    public void deleteProfileProjection(String payloadJson) {
        try {
            var payload = json.readTree(payloadJson);
            memoryIndex.delete(required(payload, "tenantId"), required(payload, "userId"),
                    sha256(required(payload, "attributeCode") + "：" + required(payload, "value")));
        } catch (Exception error) { throw new IllegalArgumentException("画像删除 Outbox payload 无效", error); }
    }

    private MemoryCandidateRow findCandidate(String id, String tenantId, String userId) {
        return db.sql("select * from memory_candidate where id=:id and tenant_id=:tenant and user_id=:user")
                .param("id", id).param("tenant", tenantId).param("user", userId)
                .query((rs, row) -> memoryCandidateRow(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("记忆候选不存在或无权访问"));
    }

    private MemoryCandidateRow memoryCandidateRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MemoryCandidateRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("source_run_id"), rs.getString("fact_key"), rs.getString("fact_value"),
                rs.getString("memory_type"), rs.getDouble("confidence"), rs.getBoolean("explicitly_confirmed"),
                rs.getInt("ttl_days"), rs.getString("status"), rs.getString("reject_reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void enqueueMemoryActivation(String tenantId, String candidateId, String sourceRunId) {
        db.sql("insert into outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json,status," +
                        "attempt_count,next_attempt_at,created_at) values(:id,:tenant,'MEMORY_CANDIDATE',:aggregate," +
                        "'MemoryCandidateApproved',:payload,'PENDING',0,current_timestamp,current_timestamp)")
                .param("id", UUID.randomUUID().toString()).param("tenant", tenantId)
                .param("aggregate", candidateId).param("payload", activationPayload(tenantId, candidateId, sourceRunId))
                .update();
    }

    private void enqueueProfileProjectionEvent(String tenantId, String userId, String sourceRunId,
                                               String eventType, String attributeCode,
                                               String value, int ttlDays) {
        try {
            String eventId = UUID.randomUUID().toString();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenantId", tenantId);
            payload.put("userId", userId);
            payload.put("sourceRunId", sourceRunId);
            payload.put("attributeCode", attributeCode);
            payload.put("value", value);
            payload.put("ttlDays", ttlDays);
            db.sql("insert into outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json," +
                            "status,attempt_count,next_attempt_at,created_at) values(:id,:tenant,'USER_PROFILE'," +
                            ":aggregate,:event,:payload,'PENDING',0,current_timestamp,current_timestamp)")
                    .param("id", eventId).param("tenant", tenantId)
                    .param("aggregate", userId + ":" + attributeCode).param("event", eventType)
                    .param("payload", json.writeValueAsString(payload)).update();
        } catch (Exception error) { throw new IllegalStateException("画像投影 Outbox 写入失败", error); }
    }

    private String activationPayload(String tenantId, String candidateId, String sourceRunId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("memoryCandidateId", candidateId);
            payload.put("sourceRunId", sourceRunId);
            db.sql("select trace_id from agent_request_dedup where tenant_id=:tenant and run_id=:run order by created_at desc limit 1")
                    .param("tenant", tenantId).param("run", sourceRunId).query(String.class).optional()
                    .ifPresent(trace -> payload.put("sourceTraceId", trace));
            return json.writeValueAsString(payload);
        } catch (Exception error) { throw new IllegalStateException("记忆激活 payload 构建失败", error); }
    }

    private void approveProfileFact(String tenantId, String userId, String runId, ModelMemoryFact fact) {
        int ttl = policy.clampProfileTtl(fact.factKey(), fact.ttlDays());
        persistence.upsertProfileFact(tenantId, userId, fact.factKey(), fact.factValue(), runId,
                Instant.now().plus(ttl, ChronoUnit.DAYS));
    }

    private boolean isReusable(String message) {
        return message != null && message.length() <= 240
                && (message.contains("偏好") || message.contains("喜欢") || message.contains("请记住")
                || message.contains("以后") || message.contains("不要推荐"))
                && !message.matches(".*(?:订单|退款|物流|余额|补偿|地址|手机号).*" )
                && !policy.containsPromptInjection(message);
    }

    private List<ModelMemoryFact> deterministicConfirmedFallback(String message) {
        if (message == null || !(message.contains("我确认") || message.contains("请记住") || message.contains("以后都")))
            return List.of();
        List<ModelMemoryFact> result = new ArrayList<>();
        for (String key : policy.allowedProfileAttributes("PRE_SALE")) {
            int index = message.indexOf(key);
            if (index < 0) continue;
            String value = message.substring(index + key.length()).replaceFirst("^[是为：: ]+", "").strip();
            if (!value.isBlank() && value.length() <= 80)
                result.add(new ModelMemoryFact(key, value, "PREFERENCE", true, 0.86, 180));
        }
        return result;
    }

    private void refreshWindowCache(MemoryScope scope) {
        if (redis == null || !redis.enabled()) return;
        try { cacheMessages(scope.conversationKey("msg-window"), persistence.loadRecentMessages(scope, recentMessages)); }
        catch (RuntimeException ignored) { degradation.increment(); }
    }

    private void cacheMessages(String key, List<MemoryPersistenceStore.StoredMessage> messages) {
        if (redis == null || !redis.enabled()) return;
        try { redis.setEx(key, messageWindowTtl, json.writeValueAsString(messages)); }
        catch (Exception ignored) { degradation.increment(); }
    }

    private List<MemoryPersistenceStore.StoredMessage> decodeMessages(String value, int limit) {
        try {
            List<MemoryPersistenceStore.StoredMessage> parsed = json.readValue(value, MESSAGE_LIST);
            if (parsed.size() <= limit) return parsed;
            return List.copyOf(parsed.subList(parsed.size() - limit, parsed.size()));
        } catch (Exception invalidCache) { return null; }
    }

    private String redact(String value) {
        return (value == null ? "" : value)
                .replaceAll("(?:OD|BT|TK)[A-Za-z0-9-]+", "[业务编号]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号]")
                .replaceAll("(?i)(?:sk|api)[-_][A-Za-z0-9_-]{12,}", "[密钥]")
                .replaceAll("\\b\\d+(?:\\.\\d{1,2})?元", "[金额]");
    }

    private List<String> lexicalTokens(String value) {
        if (value == null) return List.of();
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 2).limit(12).toList();
    }

    private String safeId(String value) {
        String normalized = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.substring(0, Math.min(120, normalized.length()));
    }

    private String required(com.fasterxml.jackson.databind.JsonNode payload, String field) {
        String value = payload.path(field).asText("").strip();
        if (value.isBlank()) throw new IllegalArgumentException("缺少字段 " + field);
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private record ScoredEpisode(MemoryPersistenceStore.StoredEpisode episode, double score) {}
    private record MemoryCandidateRow(String id, String tenantId, String userId, String sourceRunId,
                                      String factKey, String factValue, String memoryType, double confidence,
                                      boolean explicitlyConfirmed, int ttlDays, String status,
                                      String rejectReason, Instant createdAt) {
        MemoryCandidateView view() {
            return new MemoryCandidateView(id, factKey, factValue, memoryType, confidence,
                    explicitlyConfirmed, ttlDays, status, rejectReason, createdAt);
        }
    }
}
