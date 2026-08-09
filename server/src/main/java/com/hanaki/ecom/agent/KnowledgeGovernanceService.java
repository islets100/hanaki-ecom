package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.KnowledgeCandidateView;
import com.hanaki.ecom.domain.Domain.KnowledgeDecisionRequest;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 生成知识的审核、发布、版本和有效期边界；只有官方客服可把候选升级为线上知识。 */
@Service
public class KnowledgeGovernanceService {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final int minimumJudgeScore;

    public KnowledgeGovernanceService(JdbcClient db, ObjectMapper json,
                                      @Value("${agent.knowledge.minimum-judge-score:75}") int minimumJudgeScore) {
        this.db = db;
        this.json = json;
        this.minimumJudgeScore = minimumJudgeScore;
    }

    public List<KnowledgeCandidateView> candidates(SessionAccount account, String status) {
        requireOfficial(account);
        String normalized = status == null || status.isBlank() ? "READY_FOR_REVIEW" : status.toUpperCase(Locale.ROOT);
        return db.sql("select * from knowledge_candidate where status=:status order by created_at desc limit 100")
                .param("status", normalized)
                .query(this::candidate).list();
    }

    @Transactional
    public KnowledgeCandidateView decide(String id, KnowledgeDecisionRequest request, SessionAccount account) {
        requireOfficial(account);
        KnowledgeCandidateView candidate = findForUpdate(id);
        String tenantId = candidate.tenantId();
        if (!"READY_FOR_REVIEW".equals(candidate.status()) && !"PENDING_REVIEW".equals(candidate.status()))
            throw new IllegalArgumentException("该知识候选已处理，不能重复审核");
        String decision = request.decision() == null ? "" : request.decision().strip().toUpperCase(Locale.ROOT);
        if ("REJECT".equals(decision)) {
            String reason = request.comment() == null || request.comment().isBlank() ? "官方客服审核拒绝" : request.comment().strip();
            db.sql("update knowledge_candidate set status='REJECTED',reject_reason=:reason,reviewed_by=:reviewer," +
                            "reviewed_at=current_timestamp where id=:id and tenant_id=:tenant")
                    .param("reason", truncate(reason, 255)).param("reviewer", account.id())
                    .param("id", id).param("tenant", tenantId).update();
            return find(id, tenantId);
        }
        if (!"APPROVE".equals(decision)) throw new IllegalArgumentException("decision 只能是 APPROVE 或 REJECT");
        validateForPublication(candidate);
        String knowledgeId = "GEN-" + id.replaceAll("[^A-Za-z0-9]", "");
        if (knowledgeId.length() > 40) knowledgeId = knowledgeId.substring(0, 40);
        String version = "generated-" + Instant.now().toString().substring(0, 10) + "-1";
        Instant expiry = Instant.now().plus(180, ChronoUnit.DAYS);
        CandidateMetadata metadata = db.sql("select coalesce(rule_version,'unknown') rule_version," +
                        "coalesce(prompt_version,'unknown') prompt_version," +
                        "coalesce(model_version,'unknown') model_version," +
                        "coalesce(knowledge_version,'unknown') knowledge_version," +
                        "coalesce(applicable_conditions,'通用；发布后仍需校验有效期与租户') applicable_conditions " +
                        "from knowledge_candidate where id=:id and tenant_id=:tenant")
                .param("id", id).param("tenant", tenantId)
                .query((rs, row) -> new CandidateMetadata(rs.getString("rule_version"),
                        rs.getString("prompt_version"), rs.getString("model_version"),
                        rs.getString("knowledge_version"), rs.getString("applicable_conditions"))).single();
        String sourceVersionHash = sha256(metadata.knowledgeVersion() + "|" + metadata.ruleVersion()
                + "|" + metadata.promptVersion());
        db.sql("insert into knowledge_doc(id,tenant_id,domain,title,content,version,active,source_type,source_trace_id," +
                        "content_hash,effective_at,expires_at,reviewed_by,reviewed_at,rule_version,prompt_version," +
                        "model_version,applicable_conditions,lifecycle_status,source_version_hash) values(:id,:tenant,:domain,:title,:content,:version,true," +
                        "'GENERATED_REVIEWED',:trace,:hash,current_timestamp,:expiry,:reviewer,current_timestamp," +
                        ":ruleVersion,:promptVersion,:modelVersion,:conditions,'ACTIVE',:sourceVersionHash)")
                .param("id", knowledgeId).param("tenant", tenantId).param("domain", domain(candidate.intent()))
                .param("title", truncate(candidate.normalizedQuestion(), 160)).param("content", candidate.proposedAnswer())
                .param("version", version).param("trace", candidate.sourceTraceId())
                .param("hash", sha256(candidate.normalizedQuestion() + "|" + candidate.proposedAnswer()))
                .param("expiry", java.sql.Timestamp.from(expiry)).param("reviewer", account.id())
                .param("ruleVersion", metadata.ruleVersion()).param("promptVersion", metadata.promptVersion())
                .param("modelVersion", metadata.modelVersion()).param("conditions", metadata.applicableConditions())
                .param("sourceVersionHash", sourceVersionHash).update();
        saveDependency(knowledgeId, tenantId, "KNOWLEDGE", "retrieval-snapshot", metadata.knowledgeVersion());
        saveDependency(knowledgeId, tenantId, "RULE", "business-rule", metadata.ruleVersion());
        saveDependency(knowledgeId, tenantId, "PROMPT", "generation-prompt", metadata.promptVersion());
        db.sql("update knowledge_candidate set status='ACTIVE',reject_reason=null,reviewed_by=:reviewer," +
                        "reviewed_at=current_timestamp where id=:id and tenant_id=:tenant")
                .param("reviewer", account.id()).param("id", id).param("tenant", tenantId).update();
        enqueue(tenantId, id, "KnowledgeCandidateActivated", Map.of(
                "knowledgeCandidateId", id, "knowledgeDocumentId", knowledgeId,
                "sourceTraceId", candidate.sourceTraceId(), "version", version));
        return find(id, tenantId);
    }

    private void validateForPublication(KnowledgeCandidateView candidate) {
        if (candidate.judgeScore() < minimumJudgeScore) throw new IllegalArgumentException("Judge 分数低于发布门槛");
        if (candidate.evidence().isEmpty()) throw new IllegalArgumentException("没有权威证据引用，不能发布");
        String content = candidate.normalizedQuestion() + " " + candidate.proposedAnswer();
        if (candidate.intent() == Intent.IN_SALE || containsDynamic(content))
            throw new IllegalArgumentException("订单、物流、余额、金额等动态个案不能发布到公共知识库");
    }

    private boolean containsDynamic(String value) {
        return value.matches(".*(?:(?:OD|BT|TK)[A-Za-z0-9-]+|订单号|物流轨迹|余额|退款成功|补偿金额|\\d+(?:\\.\\d{1,2})?元).*" );
    }

    private KnowledgeCandidateView find(String id, String tenantId) {
        return db.sql("select * from knowledge_candidate where id=:id and tenant_id=:tenant")
                .param("id", id).param("tenant", tenantId).query(this::candidate).optional()
                .orElseThrow(() -> new IllegalArgumentException("知识候选不存在或无权访问"));
    }

    /** 审核必须锁定候选行，避免两名官方客服同时发布同一候选。 */
    private KnowledgeCandidateView findForUpdate(String id) {
        return db.sql("select * from knowledge_candidate where id=:id for update")
                .param("id", id).query(this::candidate).optional()
                .orElseThrow(() -> new IllegalArgumentException("知识候选不存在或无权访问"));
    }

    private KnowledgeCandidateView candidate(ResultSet rs, int row) throws SQLException {
        try {
            List<String> evidence = json.readValue(rs.getString("evidence_json"), new TypeReference<List<String>>() {});
            return new KnowledgeCandidateView(rs.getString("id"), rs.getString("tenant_id"), rs.getString("normalized_question"),
                    rs.getString("proposed_answer"), Intent.valueOf(rs.getString("intent")), evidence,
                    rs.getInt("judge_score"), rs.getString("source_trace_id"), rs.getString("status"),
                    rs.getString("reject_reason"), rs.getTimestamp("created_at").toInstant());
        } catch (SQLException error) { throw error; }
        catch (Exception error) { throw new SQLException("Invalid knowledge candidate evidence", error); }
    }

    private void enqueue(String tenantId, String aggregateId, String type, Object payload) {
        try {
            db.sql("insert into outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json,status," +
                            "attempt_count,next_attempt_at,created_at) values(:id,:tenant,'KNOWLEDGE_CANDIDATE',:aggregate," +
                            ":type,:payload,'PENDING',0,current_timestamp,current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("tenant", tenantId)
                    .param("aggregate", aggregateId).param("type", type)
                    .param("payload", json.writeValueAsString(payload)).update();
        } catch (Exception error) { throw new IllegalStateException("知识 Outbox 写入失败", error); }
    }

    private String domain(Intent intent) {
        return switch (intent) {
            case PRE_SALE -> "PRE_SALE";
            case AFTER_SALE -> "AFTER_SALE";
            case COMPLAINT -> "COMPLAINT";
            default -> "COMMON";
        };
    }

    private void saveDependency(String knowledgeId, String tenantId, String type, String key, String version) {
        db.sql("insert into knowledge_dependency(knowledge_doc_id,tenant_id,dependency_type,dependency_key," +
                        "dependency_version,created_at) values(:doc,:tenant,:type,:key,:version,current_timestamp)")
                .param("doc", knowledgeId).param("tenant", tenantId).param("type", type)
                .param("key", key).param("version", version).update();
    }

    private record CandidateMetadata(String ruleVersion, String promptVersion,
                                     String modelVersion, String knowledgeVersion,
                                     String applicableConditions) {}

    private void requireOfficial(SessionAccount account) {
        if (account.role() != AccountRole.OFFICIAL_AGENT)
            throw new SecurityException("只有商城官方客服可以审核生成知识");
    }
    private String truncate(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
