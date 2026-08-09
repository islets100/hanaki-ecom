package com.hanaki.ecom.agent;

import com.hanaki.ecom.memory.api.MemoryManifestItem;
import com.hanaki.ecom.memory.domain.MemoryScope;
import com.hanaki.ecom.memory.domain.MemoryTrustLevel;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Memory 的短事务持久层，也是 MySQL/H2 权威数据与上层 Memory 领域之间的适配器。
 *
 * <p>模型摘要、Embedding 和 Elasticsearch 调用必须在事务外完成；本类只执行短 SQL。每个读取
 * 方法都接收 MemoryScope 并在 SQL 中显式包含 tenant_id 和 user_id，不能依赖 Web AOP 或模型
 * 提供的身份。异步 Worker 调用同一方法时也会得到完全相同的租户隔离。</p>
 */
@Repository
public class MemoryPersistenceStore {
    private final JdbcClient db;

    public MemoryPersistenceStore(JdbcClient db) {
        this.db = db;
    }

    // ---------------------------------------------------------------------
    // 短期会话记忆：原始消息、结构化摘要与 CAS
    // ---------------------------------------------------------------------

    public Optional<SummarySnapshot> loadSummary(MemoryScope scope) {
        return db.sql("select summary,summary_json,version,coalesce(covered_start_seq,0) covered_start_seq," +
                        "coalesce(covered_end_seq,0) covered_end_seq,source_message_hash,status,updated_at " +
                        "from conversation_summary where tenant_id=:tenant and user_id=:user " +
                        "and conversation_id=:conversation and coalesce(status,'ACTIVE')='ACTIVE'")
                .param("tenant", scope.tenantId()).param("user", scope.userId())
                .param("conversation", scope.conversationId())
                .query((rs, row) -> new SummarySnapshot(
                        valueOr(rs.getString("summary_json"), rs.getString("summary")),
                        rs.getLong("version"), rs.getLong("covered_start_seq"), rs.getLong("covered_end_seq"),
                        rs.getString("source_message_hash"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public ConversationWindowStats conversationWindowStats(MemoryScope scope) {
        return db.sql("select count(*) message_count,coalesce(sum(token_count),0) token_count," +
                        "coalesce(max(message_seq),0) max_seq from conversation_message " +
                        "where tenant_id=:tenant and user_id=:user and conversation_id=:conversation " +
                        "and deleted_at is null")
                .param("tenant", scope.tenantId()).param("user", scope.userId())
                .param("conversation", scope.conversationId())
                .query((rs, row) -> new ConversationWindowStats(rs.getInt("message_count"),
                        rs.getInt("token_count"), rs.getLong("max_seq"))).single();
    }

    /**
     * 最近窗口按 seq 倒序限量后再恢复为时间正序。对升级前 seq 为空的旧消息使用 created_at 兜底；
     * 新消息始终有 seq，摘要边界不会再依赖不稳定时间戳。
     */
    public List<StoredMessage> loadRecentMessages(MemoryScope scope, int limit) {
        return db.sql("select id,coalesce(message_seq,0) message_seq,role,content,coalesce(token_count,0) token_count," +
                        "coalesce(trust_level,'USER_CLAIMED') trust_level,created_at from conversation_message " +
                        "where tenant_id=:tenant and user_id=:user and conversation_id=:conversation " +
                        "and deleted_at is null order by coalesce(message_seq,0) desc,created_at desc limit :limit")
                .param("tenant", scope.tenantId()).param("user", scope.userId())
                .param("conversation", scope.conversationId()).param("limit", Math.max(1, limit))
                .query((rs, row) -> new StoredMessage(rs.getString("id"), rs.getLong("message_seq"),
                        rs.getString("role"), rs.getString("content"), rs.getInt("token_count"),
                        trust(rs.getString("trust_level")), rs.getTimestamp("created_at").toInstant()))
                .list().reversed();
    }

    /**
     * 读取尚未被摘要覆盖的消息批次。调用方必须保留末尾热窗口，不应把刚刚仍需逐字理解的消息
     * 立即压缩掉。limit 仅限制单次模型成本；剩余消息会由后续异步任务继续处理。
     */
    public List<StoredMessage> loadMessagesAfter(MemoryScope scope, long coveredEndSeq, int limit) {
        return db.sql("select id,message_seq,role,content,coalesce(token_count,0) token_count," +
                        "coalesce(trust_level,'USER_CLAIMED') trust_level,created_at from conversation_message " +
                        "where tenant_id=:tenant and user_id=:user and conversation_id=:conversation " +
                        "and deleted_at is null and message_seq>:covered order by message_seq asc limit :limit")
                .param("tenant", scope.tenantId()).param("user", scope.userId())
                .param("conversation", scope.conversationId()).param("covered", Math.max(0, coveredEndSeq))
                .param("limit", Math.max(1, limit))
                .query((rs, row) -> new StoredMessage(rs.getString("id"), rs.getLong("message_seq"),
                        rs.getString("role"), rs.getString("content"), rs.getInt("token_count"),
                        trust(rs.getString("trust_level")), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * 当前会话的业务任务索引同步写入；它只保存引用和状态，不把订单/退款详情伪装成聊天摘要。
     */
    @Transactional
    public void upsertConversationTask(MemoryScope scope, String taskStatus, String pendingActionType,
                                       Instant expiresAt) {
        if (scope.businessTaskId().isBlank()) return;
        lockUser(scope.tenantId(), scope.userId());
        int updated = db.sql("update conversation_task_index set agent_type=:agent,task_status=:status," +
                        "pending_action_type=:pending,source_run_id=:run,version=version+1,expires_at=:expiry," +
                        "updated_at=current_timestamp where tenant_id=:tenant and user_id=:user " +
                        "and conversation_id=:conversation and business_task_id=:task")
                .param("agent", scope.agentType()).param("status", taskStatus).param("pending", pendingActionType)
                .param("run", scope.runId()).param("expiry", Timestamp.from(expiresAt))
                .param("tenant", scope.tenantId()).param("user", scope.userId())
                .param("conversation", scope.conversationId()).param("task", scope.businessTaskId()).update();
        if (updated == 0) {
            db.sql("insert into conversation_task_index(tenant_id,user_id,conversation_id,business_task_id," +
                            "agent_type,task_status,pending_action_type,source_run_id,version,expires_at,updated_at) " +
                            "values(:tenant,:user,:conversation,:task,:agent,:status,:pending,:run,1,:expiry," +
                            "current_timestamp)")
                    .param("tenant", scope.tenantId()).param("user", scope.userId())
                    .param("conversation", scope.conversationId()).param("task", scope.businessTaskId())
                    .param("agent", scope.agentType()).param("status", taskStatus).param("pending", pendingActionType)
                    .param("run", scope.runId()).param("expiry", Timestamp.from(expiresAt)).update();
        }
    }

    public List<StoredConversationTask> loadConversationTasks(MemoryScope scope) {
        return db.sql("select business_task_id,agent_type,task_status,pending_action_type,version,expires_at," +
                        "updated_at from conversation_task_index where tenant_id=:tenant and user_id=:user " +
                        "and conversation_id=:conversation and (expires_at is null or expires_at>current_timestamp) " +
                        "order by updated_at desc limit 10")
                .param("tenant", scope.tenantId()).param("user", scope.userId())
                .param("conversation", scope.conversationId())
                .query((rs, row) -> new StoredConversationTask(rs.getString("business_task_id"),
                        rs.getString("agent_type"), rs.getString("task_status"),
                        rs.getString("pending_action_type"), rs.getLong("version"),
                        instant(rs.getTimestamp("expires_at")), rs.getTimestamp("updated_at").toInstant())).list();
    }

    /**
     * 提交模型在事务外生成的新摘要。WHERE 同时比较 version 和 covered_end_seq：只要另一个 Run
     * 已经推进任一值，本次更新影响行数就是 0，调用方必须丢弃或重新计算，绝不能盲目覆盖。
     */
    @Transactional
    public boolean compareAndSetSummary(MemoryScope scope, SummarySnapshot expected, String summaryJson,
                                        long coveredStartSeq, long coveredEndSeq, String sourceHash,
                                        String modelName, String promptVersion,
                                        int inputTokens, int outputTokens) {
        if (coveredEndSeq <= expected.coveredEndSeq()) return false;
        if (expected.version() == 0) {
            int inserted = db.sql("insert into conversation_summary(tenant_id,user_id,conversation_id,summary," +
                            "summary_json,version,covered_start_seq,covered_end_seq,source_message_hash,model_name," +
                            "prompt_version,input_tokens,output_tokens,status,created_at,updated_at) " +
                            "select :tenant,:user,:conversation,:summary,:json,1,:start,:end,:hash,:model,:prompt," +
                            ":input,:output,'ACTIVE',current_timestamp,current_timestamp where not exists(" +
                            "select 1 from conversation_summary where tenant_id=:tenant and user_id=:user " +
                            "and conversation_id=:conversation)")
                    .param("tenant", scope.tenantId()).param("user", scope.userId())
                    .param("conversation", scope.conversationId()).param("summary", summaryJson)
                    .param("json", summaryJson).param("start", coveredStartSeq).param("end", coveredEndSeq)
                    .param("hash", sourceHash).param("model", modelName).param("prompt", promptVersion)
                    .param("input", Math.max(0, inputTokens)).param("output", Math.max(0, outputTokens)).update();
            return inserted == 1;
        }
        int updated = db.sql("update conversation_summary set summary=:summary,summary_json=:json," +
                        "version=version+1,covered_start_seq=:start,covered_end_seq=:end,source_message_hash=:hash," +
                        "model_name=:model,prompt_version=:prompt,input_tokens=:input,output_tokens=:output," +
                        "status='ACTIVE',updated_at=current_timestamp where tenant_id=:tenant and user_id=:user " +
                        "and conversation_id=:conversation and version=:version and covered_end_seq=:covered")
                .param("summary", summaryJson).param("json", summaryJson).param("start", coveredStartSeq)
                .param("end", coveredEndSeq).param("hash", sourceHash).param("model", modelName)
                .param("prompt", promptVersion).param("input", Math.max(0, inputTokens))
                .param("output", Math.max(0, outputTokens)).param("tenant", scope.tenantId())
                .param("user", scope.userId()).param("conversation", scope.conversationId())
                .param("version", expected.version()).param("covered", expected.coveredEndSeq()).update();
        return updated == 1;
    }

    /** 旧调用的兼容入口；仍通过 CAS，避免恢复期间把摘要版本倒退。 */
    public void upsertSummary(String tenantId, String userId, String conversationId, String summary) {
        MemoryScope scope = MemoryScope.conversation(tenantId, userId, conversationId, "", "", "SUMMARY_COMPAT");
        for (int attempt = 0; attempt < 3; attempt++) {
            SummarySnapshot expected = loadSummary(scope).orElse(SummarySnapshot.empty());
            long next = Math.max(expected.coveredEndSeq() + 1, 1);
            if (compareAndSetSummary(scope, expected, summary, Math.max(1, expected.coveredStartSeq()), next,
                    sha256(summary), "compat", "compat", 0, 0)) return;
        }
        throw new IllegalStateException("会话摘要 CAS 连续冲突");
    }

    // ---------------------------------------------------------------------
    // 长期画像：用户确认、版本覆盖和审计历史
    // ---------------------------------------------------------------------

    @Transactional
    public void upsertProfileFact(String tenantId, String userId, String factKey, String factValue,
                                  String sourceRunId, Instant expiresAt) {
        lockUser(tenantId, userId);
        upsertProfileFactLocked(tenantId, userId, factKey, factValue, sourceRunId, expiresAt);
    }

    /**
     * 模型提取永远只写 PENDING 候选。status 参数保留是为了兼容旧调用，但非 PENDING 会被拒绝，
     * 防止模型通过 explicitlyConfirmed=true 绕过真实的用户审批接口。
     */
    @Transactional
    public void saveExtractedCandidate(String candidateId, String tenantId, String userId, String sourceRunId,
                                       String factKey, String factValue, double confidence,
                                       boolean explicitlyConfirmed, int ttlDays, String status,
                                       Instant profileExpiresAt, String outboxPayloadJson) {
        if (!"PENDING".equals(status)) throw new IllegalArgumentException("模型提取结果只能进入 PENDING 候选");
        lockUser(tenantId, userId);
        int exists = db.sql("select count(*) from memory_candidate where tenant_id=:tenant and user_id=:user " +
                        "and source_run_id=:run and fact_key=:key and fact_value=:value")
                .param("tenant", tenantId).param("user", userId).param("run", sourceRunId)
                .param("key", factKey).param("value", factValue).query(Integer.class).single();
        if (exists > 0) return;
        db.sql("insert into memory_candidate(id,tenant_id,user_id,source_run_id,fact_key,fact_value,memory_type," +
                        "confidence,explicitly_confirmed,ttl_days,status,trust_level,schema_version,created_at) " +
                        "values(:id,:tenant,:user,:run,:key,:value,'PREFERENCE',:confidence,:confirmed,:ttl," +
                        "'PENDING','MODEL_EXTRACTED','profile-candidate-v2',current_timestamp)")
                .param("id", candidateId).param("tenant", tenantId).param("user", userId).param("run", sourceRunId)
                .param("key", factKey).param("value", factValue).param("confidence", confidence)
                .param("confirmed", explicitlyConfirmed).param("ttl", ttlDays).update();
    }

    private void upsertProfileFactLocked(String tenantId, String userId, String factKey, String factValue,
                                         String sourceRunId, Instant expiresAt) {
        Optional<ProfileVersion> previous = db.sql("select fact_value,source_run_id,coalesce(source_type,'USER_CONFIRMATION') source_type," +
                        "coalesce(trust_level,'USER_CONFIRMED') trust_level,coalesce(confidence,1.0) confidence," +
                        "coalesce(status,'CONFIRMED') status,coalesce(version,1) version,expires_at " +
                        "from user_profile_fact where tenant_id=:tenant and user_id=:user and fact_key=:key")
                .param("tenant", tenantId).param("user", userId).param("key", factKey)
                .query((rs, row) -> new ProfileVersion(rs.getString("fact_value"), rs.getString("source_run_id"),
                        rs.getString("source_type"), rs.getString("trust_level"), rs.getDouble("confidence"),
                        rs.getString("status"), rs.getLong("version"), instant(rs.getTimestamp("expires_at"))))
                .optional();
        if (previous.isPresent() && !previous.get().factValue().equals(factValue)) {
            ProfileVersion old = previous.get();
            db.sql("insert into user_profile_fact_history(id,tenant_id,user_id,fact_key,fact_value,source_run_id," +
                            "source_type,trust_level,confidence,status,version,expires_at,superseded_at) " +
                            "values(:id,:tenant,:user,:key,:value,:run,:source,:trust,:confidence,'SUPERSEDED'," +
                            ":version,:expiry,current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("tenant", tenantId).param("user", userId)
                    .param("key", factKey).param("value", old.factValue()).param("run", old.sourceRunId())
                    .param("source", old.sourceType()).param("trust", old.trustLevel())
                    .param("confidence", old.confidence()).param("version", old.version())
                    .param("expiry", old.expiresAt() == null ? null : Timestamp.from(old.expiresAt())).update();
            // MySQL 权威行立即停止旧偏好的召回；ES 投影删除通过同事务写出的 Outbox 最终完成。
            db.sql("update episodic_memory set status='SUPERSEDED',updated_at=current_timestamp " +
                            "where tenant_id=:tenant and user_id=:user and memory_type='PREFERENCE' " +
                            "and content=:content and status='ACTIVE'")
                    .param("tenant", tenantId).param("user", userId)
                    .param("content", factKey + "：" + old.factValue()).update();
        }
        int updated = db.sql("update user_profile_fact set fact_value=:value,source_run_id=:run,confirmed=true," +
                        "source_type='USER_CONFIRMATION',trust_level='USER_CONFIRMED',confidence=1.0,status='CONFIRMED'," +
                        "confirmed_at=current_timestamp,expires_at=:expiry,version=coalesce(version,1)+1," +
                        "updated_at=current_timestamp where tenant_id=:tenant and user_id=:user and fact_key=:key")
                .param("value", factValue).param("run", sourceRunId).param("expiry", Timestamp.from(expiresAt))
                .param("tenant", tenantId).param("user", userId).param("key", factKey).update();
        if (updated == 0) {
            db.sql("insert into user_profile_fact(tenant_id,user_id,fact_key,fact_value,source_run_id,confirmed," +
                            "source_type,trust_level,confidence,status,confirmed_at,version,expires_at,updated_at) " +
                            "values(:tenant,:user,:key,:value,:run,true,'USER_CONFIRMATION','USER_CONFIRMED',1.0," +
                            "'CONFIRMED',current_timestamp,1,:expiry,current_timestamp)")
                    .param("tenant", tenantId).param("user", userId).param("key", factKey)
                    .param("value", factValue).param("run", sourceRunId).param("expiry", Timestamp.from(expiresAt)).update();
        }
    }

    public List<StoredProfile> loadProfiles(MemoryScope scope, Set<String> allowedAttributes) {
        if (allowedAttributes == null || allowedAttributes.isEmpty()) return List.of();
        return db.sql("select fact_key,fact_value,coalesce(source_type,'USER_CONFIRMATION') source_type," +
                        "coalesce(trust_level,'USER_CONFIRMED') trust_level,coalesce(confidence,1.0) confidence," +
                        "coalesce(version,1) version,expires_at,updated_at from user_profile_fact " +
                        "where tenant_id=:tenant and user_id=:user and confirmed=true " +
                        "and coalesce(status,'CONFIRMED')='CONFIRMED' and (expires_at is null or expires_at>current_timestamp)")
                .param("tenant", scope.tenantId()).param("user", scope.userId())
                .query((rs, row) -> new StoredProfile(rs.getString("fact_key"), rs.getString("fact_value"),
                        rs.getString("source_type"), trust(rs.getString("trust_level")), rs.getDouble("confidence"),
                        rs.getLong("version"), instant(rs.getTimestamp("expires_at")),
                        rs.getTimestamp("updated_at").toInstant())).list().stream()
                .filter(value -> allowedAttributes.contains(value.attributeCode())).toList();
    }

    public Optional<StoredProfile> currentProfile(String tenantId, String userId, String attributeCode) {
        MemoryScope administrativeScope = MemoryScope.conversation(
                tenantId, userId, "profile-administration", "", "", "PROFILE_ADMIN");
        return loadProfiles(administrativeScope, Set.of(attributeCode)).stream().findFirst();
    }

    /**
     * 用户删除画像时先把当前版本复制到历史表并标记 DELETED，再禁用对应情景投影。返回旧值用于
     * 构建 ES 删除 Outbox；若字段已经删除/过期则返回 empty，接口可保持幂等。
     */
    @Transactional
    public Optional<DeletedProfile> deleteProfileFact(String tenantId, String userId, String attributeCode) {
        lockUser(tenantId, userId);
        Optional<ProfileVersion> current = db.sql("select fact_value,source_run_id," +
                        "coalesce(source_type,'USER_CONFIRMATION') source_type," +
                        "coalesce(trust_level,'USER_CONFIRMED') trust_level,coalesce(confidence,1.0) confidence," +
                        "coalesce(status,'CONFIRMED') status,coalesce(version,1) version,expires_at " +
                        "from user_profile_fact where tenant_id=:tenant and user_id=:user and fact_key=:key " +
                        "and coalesce(status,'CONFIRMED')='CONFIRMED'")
                .param("tenant", tenantId).param("user", userId).param("key", attributeCode)
                .query((rs, row) -> new ProfileVersion(rs.getString("fact_value"), rs.getString("source_run_id"),
                        rs.getString("source_type"), rs.getString("trust_level"), rs.getDouble("confidence"),
                        rs.getString("status"), rs.getLong("version"), instant(rs.getTimestamp("expires_at"))))
                .optional();
        if (current.isEmpty()) return Optional.empty();
        ProfileVersion old = current.get();
        db.sql("insert into user_profile_fact_history(id,tenant_id,user_id,fact_key,fact_value,source_run_id," +
                        "source_type,trust_level,confidence,status,version,expires_at,superseded_at) " +
                        "values(:id,:tenant,:user,:key,:value,:run,:source,:trust,:confidence,'DELETED',:version," +
                        ":expiry,current_timestamp)")
                .param("id", UUID.randomUUID().toString()).param("tenant", tenantId).param("user", userId)
                .param("key", attributeCode).param("value", old.factValue()).param("run", old.sourceRunId())
                .param("source", old.sourceType()).param("trust", old.trustLevel())
                .param("confidence", old.confidence()).param("version", old.version())
                .param("expiry", old.expiresAt() == null ? null : Timestamp.from(old.expiresAt())).update();
        db.sql("update user_profile_fact set confirmed=false,status='DELETED',version=coalesce(version,1)+1," +
                        "updated_at=current_timestamp where tenant_id=:tenant and user_id=:user and fact_key=:key")
                .param("tenant", tenantId).param("user", userId).param("key", attributeCode).update();
        db.sql("update episodic_memory set status='RETRIEVAL_DISABLED',updated_at=current_timestamp " +
                        "where tenant_id=:tenant and user_id=:user and memory_type='PREFERENCE' " +
                        "and content=:content and status='ACTIVE'")
                .param("tenant", tenantId).param("user", userId)
                .param("content", attributeCode + "：" + old.factValue()).update();
        return Optional.of(new DeletedProfile(attributeCode, old.factValue(), old.version()));
    }

    // ---------------------------------------------------------------------
    // 情景记忆：MySQL 权威行，ES 仅为可重建投影
    // ---------------------------------------------------------------------

    @Transactional
    public void saveEpisodeIfAbsent(String tenantId, String userId, String runId, String content,
                                    String contentHash, String embeddingJson, String embeddingModel,
                                    double importance, Instant expiresAt) {
        lockUser(tenantId, userId);
        int exists = db.sql("select count(*) from episodic_memory where tenant_id=:tenant and user_id=:user and content_hash=:hash")
                .param("tenant", tenantId).param("user", userId).param("hash", contentHash)
                .query(Integer.class).single();
        if (exists > 0) return;
        db.sql("insert into episodic_memory(id,tenant_id,user_id,memory_type,content,source_run_id,content_hash," +
                        "embedding_json,embedding_model,importance,status,agent_type,event_type,source_type," +
                        "source_event_id,trust_level,confidence,sensitivity_level,occurred_at,prompt_eligible_until," +
                        "retrieval_expires_at,storage_retain_until,version,expires_at,created_at,updated_at) " +
                        "values(:id,:tenant,:user,'PREFERENCE',:content," +
                        ":run,:hash,:embedding,:model,:importance,'ACTIVE','PRE_SALE','PREFERENCE_CONFIRMED'," +
                        "'USER_CONFIRMATION',:event,'USER_CONFIRMED',:importance,'PERSONAL',current_timestamp," +
                        ":expiry,:expiry,:storageExpiry,1,:expiry,current_timestamp,current_timestamp)")
                .param("id", UUID.randomUUID().toString()).param("tenant", tenantId).param("user", userId)
                .param("content", content).param("run", runId).param("hash", contentHash)
                .param("embedding", embeddingJson).param("model", embeddingModel)
                .param("importance", importance).param("event", "profile:" + contentHash)
                .param("expiry", Timestamp.from(expiresAt))
                .param("storageExpiry", Timestamp.from(expiresAt.plus(30, java.time.temporal.ChronoUnit.DAYS))).update();
    }

    public List<StoredEpisode> loadEpisodes(MemoryScope scope, int limit) {
        return db.sql("select id,content,embedding_json,importance,coalesce(confidence,0.8) confidence," +
                        "coalesce(trust_level,'USER_CONFIRMED') trust_level,coalesce(source_type,'USER_CONFIRMATION') source_type," +
                        "coalesce(version,1) version,coalesce(occurred_at,created_at) occurred_at," +
                        "coalesce(prompt_eligible_until,expires_at) prompt_eligible_until from episodic_memory " +
                        "where tenant_id=:tenant and user_id=:user and status='ACTIVE' " +
                        "and coalesce(retrieval_expires_at,expires_at)>current_timestamp " +
                        "and coalesce(prompt_eligible_until,expires_at)>current_timestamp " +
                        "and (agent_type=:agent or agent_type='UNKNOWN') order by occurred_at desc,created_at desc limit :limit")
                .param("tenant", scope.tenantId()).param("user", scope.userId()).param("agent", scope.agentType())
                .param("limit", Math.max(1, limit))
                .query((rs, row) -> new StoredEpisode(rs.getString("id"), rs.getString("content"),
                        rs.getString("embedding_json"), rs.getDouble("importance"), rs.getDouble("confidence"),
                        trust(rs.getString("trust_level")), rs.getString("source_type"), rs.getLong("version"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("prompt_eligible_until").toInstant()))
                .list();
    }

    // ---------------------------------------------------------------------
    // 访问审计：高基数标识只落表，不进入 Prometheus 标签
    // ---------------------------------------------------------------------

    @Transactional
    public void saveAccessAudit(MemoryScope scope, String traceId, List<MemoryManifestItem> items) {
        for (MemoryManifestItem item : items == null ? List.<MemoryManifestItem>of() : items) {
            db.sql("insert into memory_access_audit(id,trace_id,tenant_id,user_id_hash,conversation_id,run_id," +
                            "node_name,memory_id,memory_type,source_type,trust_level,source_version,retrieval_score," +
                            "access_action,access_result,token_count,created_at) values(:id,:trace,:tenant,:userHash," +
                            ":conversation,:run,:node,:memory,:type,:source,:trust,:version,:score,'PROMPT_BUILD'," +
                            ":result,:tokens,current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("trace", blankToNull(traceId))
                    .param("tenant", scope.tenantId()).param("userHash", sha256(scope.userId()))
                    .param("conversation", scope.conversationId()).param("run", blankToNull(scope.runId()))
                    .param("node", scope.nodeName()).param("memory", item.memoryId())
                    .param("type", item.layer().name()).param("source", item.sourceType())
                    .param("trust", item.trustLevel().name()).param("version", blankToNull(item.version()))
                    .param("score", item.retrievalScore()).param("result", item.decisionReason())
                    .param("tokens", item.tokenCount()).update();
        }
    }

    private void lockUser(String tenantId, String userId) {
        db.sql("select id from app_account where id=:user and " +
                        "(tenant_id=:tenant or (tenant_id='platform' and role='CUSTOMER')) for update")
                .param("tenant", tenantId).param("user", userId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("记忆所属用户不存在"));
    }

    private MemoryTrustLevel trust(String value) {
        try { return MemoryTrustLevel.valueOf(value); }
        catch (Exception ignored) { return MemoryTrustLevel.EXTERNAL_UNVERIFIED; }
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String valueOr(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    public record SummarySnapshot(String summaryJson, long version, long coveredStartSeq,
                                  long coveredEndSeq, String sourceMessageHash, Instant updatedAt) {
        public static SummarySnapshot empty() { return new SummarySnapshot("", 0, 0, 0, "", Instant.EPOCH); }
    }
    public record ConversationWindowStats(int messageCount, int tokenCount, long maxSeq) {}
    public record StoredMessage(String id, long seq, String role, String content, int tokenCount,
                                MemoryTrustLevel trustLevel, Instant createdAt) {}
    public record StoredConversationTask(String businessTaskId, String agentType, String taskStatus,
                                         String pendingActionType, long version,
                                         Instant expiresAt, Instant updatedAt) {}
    public record StoredProfile(String attributeCode, String value, String sourceType,
                                MemoryTrustLevel trustLevel, double confidence, long version,
                                Instant expiresAt, Instant updatedAt) {}
    public record StoredEpisode(String id, String content, String embeddingJson, double importance,
                                double confidence, MemoryTrustLevel trustLevel, String sourceType,
                                long version, Instant occurredAt, Instant promptEligibleUntil) {}
    public record DeletedProfile(String attributeCode, String value, long version) {}
    private record ProfileVersion(String factValue, String sourceRunId, String sourceType, String trustLevel,
                                  double confidence, String status, long version, Instant expiresAt) {}
}
