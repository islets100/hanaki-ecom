package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.CandidateProfile;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.EvaluationDecision;
import com.hanaki.ecom.domain.Domain.EvaluationTriggerMode;
import com.hanaki.ecom.domain.Domain.JudgeCandidateScore;
import com.hanaki.ecom.domain.Domain.JudgeOutcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 评审数据库是恢复与审计的事实源；Graph checkpoint 只保存运行位置和这些记录的引用。
 * 逻辑候选与物理 attempt 分开持久化，因此超时、重试和服务重启都不会覆盖历史证据。
 *
 * <p>evaluation_batch 表示逻辑批次；evaluation_snapshot 保存一次性冻结的事实；candidate_answer
 * 表示三个逻辑候选槽位；candidate_attempt/judge_attempt 保存每次真实外部调用；judge_result 保存
 * 服务端最终裁决。这个拆分使“候选 1 第一次超时、第二次成功”可以完整回放，而不是只看到最后值。</p>
 *
 * <p>所有恢复入口都重新校验 tenant/user/run 身份和 snapshotHash。状态转换采用 status + version
 * 乐观锁并且只允许单调前进，防止两个恢复线程把同一批次推进到相互矛盾的阶段。</p>
 */
@Repository
public class EvaluationStore {
    private static final String SCORING_VERSION = "judge-score-v2";
    /** 正常路径的严格单调顺序；isPast 用它识别恢复时已经完成的旧节点。 */
    private static final List<String> ORDERED_STATUSES = List.of(
            "CREATED", "SNAPSHOT_CREATING", "SNAPSHOT_READY", "CANDIDATES_RUNNING",
            "CANDIDATES_READY", "HARD_VALIDATING", "HARD_VALIDATED", "JUDGING",
            "JUDGED", "SELECTING", "SELECTED", "KNOWLEDGE_PENDING", "COMPLETED");
    /** 终态永远不能被 Graph checkpoint 重放成运行态。 */
    private static final List<String> TERMINAL_STATUSES = List.of(
            "COMPLETED", "PARTIAL_SUCCESS", "DEGRADED", "HUMAN_REVIEW_REQUIRED", "CANCELLED", "FAILED");

    private final JdbcClient db;
    private final ObjectMapper json;
    private final ObjectMapper canonicalJson;

    public EvaluationStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
        // 普通 json 用于读写；canonicalJson 对 Map 键排序，专门用于生成跨进程稳定的内容哈希。
        this.canonicalJson = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /** 旧调用兼容入口；正式路径应传入真实触发原因和当时生效的阈值。 */
    public void createBatch(EvaluationContextSnapshot snapshot, String traceId) {
        createBatch(snapshot, traceId, new EvaluationDecision(true, EvaluationTriggerMode.PRE_GENERATION,
                "LEGACY_CALL", "best-of-three-v2"), 75, 5);
    }

    /**
     * 幂等创建逻辑批次。batchId 冲突只有在 tenantId、userId、runId 完全相同时才视为安全重放；
     * 否则抛 SecurityException，防止攻击者用已知 batchId 读取或覆盖其它用户的评审。
     */
    @Transactional
    public void createBatch(EvaluationContextSnapshot snapshot, String traceId, EvaluationDecision decision,
                            int qualityThreshold, int scoreGapThreshold) {
        if (batchExists(snapshot.evaluationBatchId())) {
            assertBatchIdentity(snapshot);
            return;
        }
        try {
            db.sql("insert into evaluation_batch(id,tenant_id,user_id,conversation_id,run_id,trace_id,intent," +
                            "status,snapshot_json,trigger_mode,trigger_reason,evaluation_profile,candidate_count," +
                            "quality_threshold,score_gap_threshold,scoring_version,version,created_at,updated_at) " +
                            "values(:id,:tenant,:user,:conversation,:run,:trace,:intent,'CREATED',:snapshot,:mode," +
                            ":reason,:profile,3,:quality,:gap,:scoring,0,current_timestamp,current_timestamp)")
                    .param("id", snapshot.evaluationBatchId()).param("tenant", snapshot.tenantId())
                    .param("user", snapshot.userId()).param("conversation", snapshot.conversationId())
                    .param("run", snapshot.runId()).param("trace", traceId)
                    .param("intent", snapshot.intent().name()).param("snapshot", write(snapshot))
                    .param("mode", decision.triggerMode().name()).param("reason", truncate(decision.reason(), 255))
                    .param("profile", decision.evaluationProfile()).param("quality", qualityThreshold)
                    .param("gap", scoreGapThreshold).param("scoring", SCORING_VERSION).update();
        } catch (RuntimeException race) {
            // 两线程同时 INSERT 时，唯一键失败的一方重新读取并校验身份，而不是盲目吞掉异常。
            if (!batchExists(snapshot.evaluationBatchId())) throw race;
            assertBatchIdentity(snapshot);
        }
    }

    /** 只用于幂等竞争判断，不代表调用方已经通过批次身份校验。 */
    public boolean batchExists(String batchId) {
        return db.sql("select count(*) from evaluation_batch where id=:id").param("id", batchId)
                .query(Integer.class).single() > 0;
    }

    /** 将外部 batchId 与数据库中的安全主体重新绑定，任何一个身份维度不一致都拒绝。 */
    private void assertBatchIdentity(EvaluationContextSnapshot snapshot) {
        BatchIdentity identity = db.sql("select tenant_id,user_id,run_id from evaluation_batch where id=:id")
                .param("id", snapshot.evaluationBatchId()).query((rs, row) -> new BatchIdentity(
                        rs.getString("tenant_id"), rs.getString("user_id"), rs.getString("run_id"))).single();
        if (!identity.tenantId().equals(snapshot.tenantId()) || !identity.userId().equals(snapshot.userId())
                || !identity.runId().equals(snapshot.runId()))
            throw new SecurityException("evaluationBatchId 已被不同租户、用户或 Run 占用");
    }

    /**
     * 首次写入后快照不可更新；再次恢复时必须同时通过数据库哈希和重算哈希校验。
     *
     * <p>冻结包含深度不可变的业务事实、证据版本、Prompt/规则版本和风险标签。先插 snapshot，后以
     * 条件 UPDATE 绑定 batch；并发插入时只接受已经存在且哈希有效的记录，保证三个候选无论何时
     * 启动都看到完全相同的事实世界。</p>
     */
    @Transactional
    public EvaluationContextSnapshot freezeSnapshot(EvaluationContextSnapshot draft) {
        Optional<EvaluationContextSnapshot> existing = loadSnapshot(draft.evaluationBatchId());
        if (existing.isPresent()) {
            EvaluationContextSnapshot restored = existing.get();
            verifySnapshot(restored);
            if (!restored.tenantId().equals(draft.tenantId()) || !restored.userId().equals(draft.userId())
                    || !restored.runId().equals(draft.runId()))
                throw new SecurityException("恢复的评审快照不属于当前安全上下文");
            ensureSnapshotReady(restored);
            return restored;
        }

        advanceStatus(draft.evaluationBatchId(), "SNAPSHOT_CREATING", "CREATED");
        EvaluationContextSnapshot normalized = normalize(draft, "");
        String hash = snapshotHash(normalized);
        EvaluationContextSnapshot sealed = normalize(normalized, hash);
        try {
            db.sql("insert into evaluation_snapshot(id,evaluation_batch_id,tenant_id,user_id,conversation_id,run_id," +
                            "intent,normalized_question,snapshot_json,snapshot_hash,created_at) values(:id,:batch," +
                            ":tenant,:user,:conversation,:run,:intent,:question,:snapshot,:hash,current_timestamp)")
                    .param("id", sealed.snapshotId()).param("batch", sealed.evaluationBatchId())
                    .param("tenant", sealed.tenantId()).param("user", sealed.userId())
                    .param("conversation", sealed.conversationId()).param("run", sealed.runId())
                    .param("intent", sealed.intent().name()).param("question", normalizeQuestion(sealed.originalQuestion()))
                    .param("snapshot", write(sealed)).param("hash", sealed.snapshotHash()).update();
        } catch (RuntimeException race) {
            Optional<EvaluationContextSnapshot> raced = loadSnapshot(draft.evaluationBatchId());
            if (raced.isEmpty()) throw race;
            verifySnapshot(raced.get());
            ensureSnapshotReady(raced.get());
            return raced.get();
        }
        db.sql("update evaluation_batch set snapshot_id=:snapshotId,snapshot_hash=:hash,snapshot_json=:snapshot," +
                        "status='SNAPSHOT_READY',version=version+1,updated_at=current_timestamp " +
                        "where id=:batch and status='SNAPSHOT_CREATING'")
                .param("snapshotId", sealed.snapshotId()).param("hash", sealed.snapshotHash())
                .param("snapshot", write(sealed)).param("batch", sealed.evaluationBatchId()).update();
        return sealed;
    }

    /** 修复“snapshot 已插入但进程在 batch 更新前退出”的半完成状态，只允许从前两个状态补齐。 */
    private void ensureSnapshotReady(EvaluationContextSnapshot snapshot) {
        db.sql("update evaluation_batch set snapshot_id=:snapshotId,snapshot_hash=:hash,snapshot_json=:snapshot," +
                        "status='SNAPSHOT_READY',version=version+1,updated_at=current_timestamp where id=:batch " +
                        "and status in ('CREATED','SNAPSHOT_CREATING')")
                .param("snapshotId", snapshot.snapshotId()).param("hash", snapshot.snapshotHash())
                .param("snapshot", write(snapshot)).param("batch", snapshot.evaluationBatchId()).update();
    }

    /** 从独立快照表恢复，并先比较数据库 hash 列与 JSON 内嵌 hash，检测部分写入或人工篡改。 */
    public Optional<EvaluationContextSnapshot> loadSnapshot(String batchId) {
        return db.sql("select snapshot_json,snapshot_hash from evaluation_snapshot where evaluation_batch_id=:batch")
                .param("batch", batchId).query((rs, row) -> {
                    try {
                        EvaluationContextSnapshot value = json.readValue(rs.getString("snapshot_json"),
                                EvaluationContextSnapshot.class);
                        if (!rs.getString("snapshot_hash").equals(value.snapshotHash()))
                            throw new IllegalStateException("评审快照数据库哈希与内容字段不一致");
                        return normalize(value, value.snapshotHash());
                    } catch (Exception error) { throw new IllegalStateException("评审快照无法恢复", error); }
                }).optional();
    }

    /** 对内存对象重新做 canonical 序列化和 SHA-256；字段被改动一位也不能继续调用模型。 */
    public void verifySnapshot(EvaluationContextSnapshot snapshot) {
        if (snapshot == null || snapshot.snapshotHash() == null || snapshot.snapshotHash().isBlank())
            throw new IllegalStateException("评审快照尚未封存");
        String actual = snapshotHash(normalize(snapshot, ""));
        if (!actual.equals(snapshot.snapshotHash())) throw new IllegalStateException("评审快照完整性校验失败");
    }

    /**
     * 构造快照哈希材料。snapshotHash 自身不参与计算，避免递归；所有影响回答的输入均显式列出，
     * 后续新增关键字段时必须同步加入这里并提升协议版本。
     */
    public String snapshotHash(EvaluationContextSnapshot snapshot) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("snapshotId", snapshot.snapshotId());
        material.put("evaluationBatchId", snapshot.evaluationBatchId());
        material.put("tenantId", snapshot.tenantId());
        material.put("userId", snapshot.userId());
        material.put("conversationId", snapshot.conversationId());
        material.put("runId", snapshot.runId());
        material.put("originalQuestion", snapshot.originalQuestion());
        material.put("intent", snapshot.intent().name());
        material.put("recentMessages", snapshot.recentMessages());
        material.put("evidence", snapshot.evidence());
        material.put("businessFacts", snapshot.businessFacts());
        material.put("knowledgeVersion", snapshot.knowledgeVersion());
        material.put("ruleVersion", snapshot.ruleVersion());
        material.put("promptVersion", snapshot.promptVersion());
        material.put("riskTags", snapshot.riskTags());
        material.put("createdAt", snapshot.createdAt());
        return sha256(writeCanonical(material));
    }

    /**
     * 使用 status + version 的 compare-and-set 推进状态。目标已完成或当前状态更靠后时返回 false，
     * 便于 checkpoint 幂等重放；不在 allowedFrom 中则是编排错误；CAS 失败说明发生并发恢复，应从
     * 数据库重新装载而不是覆盖另一线程结果。
     */
    public boolean advanceStatus(String batchId, String target, String... allowedFrom) {
        BatchVersion batch = db.sql("select status,version from evaluation_batch where id=:id")
                .param("id", batchId).query((rs, row) -> new BatchVersion(rs.getString("status"), rs.getInt("version")))
                .optional().orElseThrow(() -> new IllegalStateException("评审批次不存在: " + batchId));
        if (batch.status().equals(target) || isPast(batch.status(), target)) return false;
        if (List.of(allowedFrom).stream().noneMatch(batch.status()::equals))
            throw new IllegalStateException("非法评审状态转换: " + batch.status() + " -> " + target);
        int changed = db.sql("update evaluation_batch set status=:target,version=version+1,updated_at=current_timestamp " +
                        "where id=:id and status=:current and version=:version")
                .param("target", target).param("id", batchId).param("current", batch.status())
                .param("version", batch.version()).update();
        if (changed != 1) throw new IllegalStateException("评审状态被并发修改，请从数据库恢复后重试");
        return true;
    }

    /** 终态视为越过所有普通阶段；正常态按 ORDERED_STATUSES 的索引比较。 */
    private boolean isPast(String current, String target) {
        if (TERMINAL_STATUSES.contains(current)) return true;
        int currentIndex = ORDERED_STATUSES.indexOf(current);
        int targetIndex = ORDERED_STATUSES.indexOf(target);
        return currentIndex >= 0 && targetIndex >= 0 && currentIndex > targetIndex;
    }

    /**
     * 恢复某一逻辑候选槽位已经成功的有效答案。只读取 SUCCEEDED，不复用 FAILED/REJECTED 草稿；
     * 同时恢复 effectiveAttemptId、画像、Token 和耗时，以便新的批次聚合保持完整审计信息。
     */
    public Optional<RecoveredCandidate> completedCandidate(String batchId, int candidateNo) {
        return db.sql("select candidate_id,candidate_run_id,effective_attempt_id,candidate_profile,answer,evidence_json," +
                        "tool_result_json,safe,completeness,clarity,prompt_tokens,completion_tokens,elapsed_ms " +
                        "from candidate_answer where evaluation_batch_id=:batch and candidate_no=:no and status='SUCCEEDED'")
                .param("batch", batchId).param("no", candidateNo).query((rs, row) -> {
                    try {
                        AgentDraft draft = new AgentDraft(rs.getString("candidate_id"), rs.getString("answer"),
                                readStrings(rs.getString("evidence_json")), readStrings(rs.getString("tool_result_json")),
                                rs.getBoolean("safe"), rs.getInt("completeness"), rs.getInt("clarity"));
                        String profile = rs.getString("candidate_profile");
                        return new RecoveredCandidate(draft, rs.getString("candidate_run_id"),
                                rs.getString("effective_attempt_id"), profile == null ? CandidateProfile.forCandidate(candidateNo)
                                : CandidateProfile.valueOf(profile), rs.getInt("prompt_tokens"),
                                rs.getInt("completion_tokens"), rs.getLong("elapsed_ms"));
                    } catch (Exception error) { throw new IllegalStateException("候选审计数据无法恢复", error); }
                }).optional();
    }

    /**
     * 在行锁保护下创建下一个候选 attempt。attemptNo 单调递增且受 maxAttempts 限制；requestHash
     * 绑定 snapshotHash、候选画像和次数，使一次物理模型请求可以被唯一重放和核查。
     */
    @Transactional
    public Optional<CandidateAttempt> startCandidateAttempt(EvaluationContextSnapshot snapshot, int candidateNo,
                                                             CandidateProfile profile, int maxAttempts) {
        verifySnapshot(snapshot);
        lockBatch(snapshot.evaluationBatchId());
        int previous = db.sql("select coalesce(max(attempt_no),0) from candidate_attempt " +
                        "where evaluation_batch_id=:batch and candidate_no=:no")
                .param("batch", snapshot.evaluationBatchId()).param("no", candidateNo)
                .query(Integer.class).single();
        if (previous >= maxAttempts) return Optional.empty();
        int attemptNo = previous + 1;
        String attemptId = snapshot.evaluationBatchId() + "-c" + candidateNo + "-a" + attemptNo;
        String candidateRunId = snapshot.evaluationBatchId() + "-candidate-" + candidateNo + "-attempt-" + attemptNo;
        String requestHash = sha256(snapshot.snapshotHash() + "|" + profile.name() + "|" + attemptNo);
        db.sql("insert into candidate_attempt(id,evaluation_batch_id,candidate_no,attempt_no,candidate_profile," +
                        "candidate_run_id,status,model_version,prompt_version,snapshot_hash,request_hash,created_at,updated_at) " +
                        "values(:id,:batch,:no,:attempt,:profile,:run,'RUNNING',:model,:prompt,:snapshot,:request," +
                        "current_timestamp,current_timestamp)")
                .param("id", attemptId).param("batch", snapshot.evaluationBatchId()).param("no", candidateNo)
                .param("attempt", attemptNo).param("profile", profile.name()).param("run", candidateRunId)
                .param("model", String.valueOf(snapshot.businessFacts().getOrDefault("modelVersion", "unknown")))
                .param("prompt", snapshot.promptVersion()).param("snapshot", snapshot.snapshotHash())
                .param("request", requestHash).update();
        return Optional.of(new CandidateAttempt(attemptId, attemptNo, candidateRunId, profile));
    }

    /** 仅允许 RUNNING → SUCCEEDED；同时保存原回答、结构化结果、响应哈希、Token 与耗时。 */
    public void completeCandidateAttempt(String attemptId, AgentDraft draft, long elapsedMs,
                                         int promptTokens, int completionTokens) {
        int changed = db.sql("update candidate_attempt set status='SUCCEEDED',response_hash=:hash,raw_response=:raw," +
                        "parsed_response_json=:parsed,prompt_tokens=:promptTokens,completion_tokens=:completionTokens," +
                        "elapsed_ms=:elapsed,updated_at=current_timestamp where id=:id and status='RUNNING'")
                .param("hash", sha256(writeCanonical(draft))).param("raw", draft.answer())
                .param("parsed", write(draft)).param("promptTokens", promptTokens)
                .param("completionTokens", completionTokens).param("elapsed", elapsedMs).param("id", attemptId).update();
        if (changed != 1) throw new IllegalStateException("候选 attempt 已结束或不存在: " + attemptId);
    }

    /** 将失败 attempt 终结；错误类型保留，第三方消息截断到 2000 字符，避免异常响应撑大审计库。 */
    public void failCandidateAttempt(String attemptId, Throwable error, long elapsedMs) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        db.sql("update candidate_attempt set status='FAILED',elapsed_ms=:elapsed,error_type=:type,error_message=:error," +
                        "updated_at=current_timestamp where id=:id and status='RUNNING'")
                .param("elapsed", elapsedMs).param("type", error.getClass().getName())
                .param("error", truncate(message, 2000)).param("id", attemptId).update();
    }

    /**
     * 将物理 attempt 的结果投影到固定 candidateNo 逻辑槽位。后续重试成功可以更新有效槽位，旧
     * attempt 行仍完整保留；硬校验审计码与模型输出分开保存，便于区分“模型说了什么”和“为何拒绝”。
     */
    @Transactional
    public void saveCandidate(String batchId, int candidateNo, String candidateRunId, String attemptId,
                              CandidateProfile profile, AgentDraft draft, long elapsedMs, List<String> validations,
                              int promptTokens, int completionTokens) {
        lockBatch(batchId);
        int updated = db.sql("update candidate_answer set candidate_id=:candidate,candidate_run_id=:run," +
                        "effective_attempt_id=:attempt,candidate_profile=:profile,status=:status,answer=:answer," +
                        "answer_hash=:answerHash,evidence_json=:evidence,tool_result_json=:tools,validation_json=:validation," +
                        "risk_tags_json=:risk,safe=:safe,completeness=:completeness,clarity=:clarity," +
                        "prompt_tokens=:promptTokens,completion_tokens=:completionTokens,elapsed_ms=:elapsed," +
                        "error_type=null,error_message=null where evaluation_batch_id=:batch and candidate_no=:no " +
                        "and status<>'SUCCEEDED'")
                .param("batch", batchId).param("no", candidateNo).param("candidate", draft.candidateId())
                .param("run", candidateRunId).param("attempt", attemptId).param("profile", profile.name())
                .param("status", draft.safe() ? "SUCCEEDED" : "REJECTED").param("answer", draft.answer())
                .param("answerHash", sha256(draft.answer())).param("evidence", write(draft.evidence()))
                .param("tools", write(draft.toolResults())).param("validation", write(validations))
                .param("risk", write(validations.stream().filter(v -> v.startsWith("VIOLATION:")).toList()))
                .param("safe", draft.safe()).param("completeness", draft.completeness()).param("clarity", draft.clarity())
                .param("promptTokens", promptTokens).param("completionTokens", completionTokens)
                .param("elapsed", elapsedMs).update();
        if (updated == 0 && completedCandidate(batchId, candidateNo).isEmpty()) {
            db.sql("insert into candidate_answer(id,evaluation_batch_id,candidate_no,candidate_id,candidate_run_id," +
                            "effective_attempt_id,candidate_profile,status,answer,answer_hash,evidence_json,tool_result_json," +
                            "validation_json,risk_tags_json,safe,completeness,clarity,prompt_tokens,completion_tokens," +
                            "elapsed_ms,created_at) values(:id,:batch,:no,:candidate,:run,:attempt,:profile,:status,:answer," +
                            ":answerHash,:evidence,:tools,:validation,:risk,:safe,:completeness,:clarity,:promptTokens," +
                            ":completionTokens,:elapsed,current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("batch", batchId).param("no", candidateNo)
                    .param("candidate", draft.candidateId()).param("run", candidateRunId).param("attempt", attemptId)
                    .param("profile", profile.name()).param("status", draft.safe() ? "SUCCEEDED" : "REJECTED")
                    .param("answer", draft.answer()).param("answerHash", sha256(draft.answer()))
                    .param("evidence", write(draft.evidence())).param("tools", write(draft.toolResults()))
                    .param("validation", write(validations)).param("risk", write(validations.stream()
                            .filter(v -> v.startsWith("VIOLATION:")).toList()))
                    .param("safe", draft.safe()).param("completeness", draft.completeness())
                    .param("clarity", draft.clarity()).param("promptTokens", promptTokens)
                    .param("completionTokens", completionTokens).param("elapsed", elapsedMs).update();
        }
    }

    /** 无 attemptId 的兼容/安全兜底入口，最终仍落到同一逻辑候选表。 */
    public void saveCandidate(String batchId, int candidateNo, String candidateRunId,
                              AgentDraft draft, long elapsedMs, List<String> validations,
                              int promptTokens, int completionTokens) {
        saveCandidate(batchId, candidateNo, candidateRunId, null,
                candidateNo >= 1 && candidateNo <= 3 ? CandidateProfile.forCandidate(candidateNo)
                        : CandidateProfile.FACT_AND_EVIDENCE,
                draft, elapsedMs, validations, promptTokens, completionTokens);
    }

    @Transactional
    /**
     * 即使一个分支所有 attempt 都失败，也写入 candidate_answer 的失败占位，保证聚合与后台审计
     * 始终能看到固定三个候选槽位，而不是把“缺记录”误认为“从未计划该分支”。
     */
    public void saveCandidateFailure(String batchId, int candidateNo, String candidateRunId,
                                     Throwable error, long elapsedMs) {
        lockBatch(batchId);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        int updated = db.sql("update candidate_answer set candidate_run_id=:run,status='FAILED',elapsed_ms=:elapsed," +
                        "error_type=:type,error_message=:error,retry_count=retry_count+1 " +
                        "where evaluation_batch_id=:batch and candidate_no=:no and status<>'SUCCEEDED'")
                .param("batch", batchId).param("no", candidateNo).param("run", candidateRunId)
                .param("elapsed", elapsedMs).param("type", error.getClass().getName())
                .param("error", truncate(message, 2000)).update();
        if (updated == 0 && completedCandidate(batchId, candidateNo).isEmpty()) {
            db.sql("insert into candidate_answer(id,evaluation_batch_id,candidate_no,candidate_id,candidate_run_id," +
                            "candidate_profile,status,answer,evidence_json,tool_result_json,validation_json,risk_tags_json," +
                            "safe,completeness,clarity,elapsed_ms,error_type,error_message,retry_count,created_at) " +
                            "values(:id,:batch,:no,:candidate,:run,:profile,'FAILED','','[]','[]','[\"BRANCH_FAILED\"]'," +
                            "'[\"BRANCH_FAILED\"]',false,0,0,:elapsed,:type,:error,1,current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("batch", batchId).param("no", candidateNo)
                    .param("candidate", "C" + candidateNo).param("run", candidateRunId)
                    .param("profile", CandidateProfile.forCandidate(candidateNo).name()).param("elapsed", elapsedMs)
                    .param("type", error.getClass().getName()).param("error", truncate(message, 2000)).update();
        }
    }

    /** 保存硬校验后的成功数，而不是仅保存模型调用成功数。 */
    public void updateSuccessfulCount(String batchId, int count) {
        db.sql("update evaluation_batch set successful_count=:count,updated_at=current_timestamp where id=:id")
                .param("count", count).param("id", batchId).update();
    }

    /** 按候选 attempt 的真实输入+输出 Token 计算租户当日用量；失败但已计量的调用同样计入。 */
    public long tenantTokensToday(String tenantId) {
        Long value = db.sql("select coalesce(sum(a.prompt_tokens+a.completion_tokens),0) from candidate_attempt a " +
                        "join evaluation_batch b on b.id=a.evaluation_batch_id where b.tenant_id=:tenant " +
                        "and a.created_at>=current_date")
                .param("tenant", tenantId).query(Long.class).single();
        return value == null ? 0L : value;
    }

    /**
     * 从终态 batch + judge_result + winner candidate 三表重建 Outcome。只有明确终态才可复用，
     * 避免读取 SELECTING 等事务中间状态作为最终客服回答。
     */
    public Optional<JudgeOutcome> completedOutcome(String batchId) {
        return db.sql("select c.candidate_id,c.answer,c.evidence_json,c.tool_result_json,c.safe,c.completeness,c.clarity," +
                        "j.scores_json,j.score_gap,j.needs_human_review,j.fallback_reason from evaluation_batch b " +
                        "join judge_result j on j.evaluation_batch_id=b.id join candidate_answer c " +
                        "on c.evaluation_batch_id=b.id and c.candidate_id=j.selected_candidate_id " +
                        "where b.id=:batch and b.status in ('COMPLETED','HUMAN_REVIEW_REQUIRED','NEEDS_REVIEW')")
                .param("batch", batchId).query((rs, row) -> {
                    try {
                        AgentDraft winner = new AgentDraft(rs.getString("candidate_id"), rs.getString("answer"),
                                readStrings(rs.getString("evidence_json")), readStrings(rs.getString("tool_result_json")),
                                rs.getBoolean("safe"), rs.getInt("completeness"), rs.getInt("clarity"));
                        List<JudgeCandidateScore> scores = json.readValue(rs.getString("scores_json"),
                                new TypeReference<List<JudgeCandidateScore>>() {});
                        return new JudgeOutcome(winner, scores, rs.getInt("score_gap"),
                                rs.getBoolean("needs_human_review"), rs.getString("fallback_reason"));
                    } catch (Exception error) { throw new IllegalStateException("评审批次无法恢复", error); }
                }).optional();
    }

    /**
     * 创建独立 Judge attempt。candidateSetHash 绑定本次成功候选集合，模型/Prompt 版本同时入库；
     * 如果候选集合发生变化，旧 Judge 响应不能被当作新集合的裁决。
     */
    @Transactional
    public Optional<JudgeAttempt> startJudgeAttempt(EvaluationContextSnapshot snapshot, String candidateSetHash,
                                                     int maxAttempts, String modelVersion) {
        verifySnapshot(snapshot);
        lockBatch(snapshot.evaluationBatchId());
        int previous = db.sql("select coalesce(max(attempt_no),0) from judge_attempt where evaluation_batch_id=:batch")
                .param("batch", snapshot.evaluationBatchId()).query(Integer.class).single();
        if (previous >= maxAttempts) return Optional.empty();
        int attemptNo = previous + 1;
        String id = snapshot.evaluationBatchId() + "-judge-a" + attemptNo;
        db.sql("insert into judge_attempt(id,evaluation_batch_id,attempt_no,status,candidate_set_hash,model_version," +
                        "prompt_version,request_hash,created_at,updated_at) values(:id,:batch,:attempt,'RUNNING'," +
                        ":setHash,:model,:prompt,:request,current_timestamp,current_timestamp)")
                .param("id", id).param("batch", snapshot.evaluationBatchId()).param("attempt", attemptNo)
                .param("setHash", candidateSetHash).param("model", modelVersion)
                .param("prompt", snapshot.promptVersion()).param("request", candidateSetHash).update();
        return Optional.of(new JudgeAttempt(id, attemptNo, candidateSetHash));
    }

    /** 保存 Judge 原始选择、结构化 Outcome 与响应哈希；最终服务端 winner 仍在 complete 中提交。 */
    public void completeJudgeAttempt(String attemptId, JudgeOutcome outcome, long elapsedMs) {
        db.sql("update judge_attempt set status='SUCCEEDED',response_hash=:hash,raw_response=:raw," +
                        "parsed_response_json=:parsed,elapsed_ms=:elapsed,updated_at=current_timestamp " +
                        "where id=:id and status='RUNNING'")
                .param("hash", sha256(writeCanonical(outcome))).param("raw", outcome.winner().candidateId())
                .param("parsed", write(outcome)).param("elapsed", elapsedMs).param("id", attemptId).update();
    }

    /** 终结失败 Judge attempt，供有限重试和 deterministicFallback 的原因审计。 */
    public void failJudgeAttempt(String attemptId, Throwable error, long elapsedMs) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        db.sql("update judge_attempt set status='FAILED',error_type=:type,error_message=:error,elapsed_ms=:elapsed," +
                        "updated_at=current_timestamp where id=:id and status='RUNNING'")
                .param("type", error.getClass().getName()).param("error", truncate(message, 2000))
                .param("elapsed", elapsedMs).param("id", attemptId).update();
    }

    /** 兼容无显式 Judge attempt 的确定性降级完成路径。 */
    @Transactional
    public void complete(EvaluationContextSnapshot snapshot, JudgeOutcome outcome, String traceId) {
        complete(snapshot, outcome, traceId, null, candidateSetHash(outcome.scores()));
    }

    /**
     * 原子提交最终裁决：重新校验快照、锁定批次、保存当时生效的阈值和评分版本、更新批次终态，
     * 最后生成知识候选 Outbox。judge_result 使用 upsert 语义支持安全重放，但 batch 身份和快照
     * 在此之前已经校验，不能跨租户覆盖。
     */
    @Transactional
    public void complete(EvaluationContextSnapshot snapshot, JudgeOutcome outcome, String traceId,
                         String judgeAttemptId, String candidateSetHash) {
        verifySnapshot(snapshot);
        lockBatch(snapshot.evaluationBatchId());
        BatchThresholds thresholds = db.sql("select quality_threshold,score_gap_threshold,scoring_version " +
                        "from evaluation_batch where id=:id")
                .param("id", snapshot.evaluationBatchId()).query((rs, row) -> new BatchThresholds(
                        rs.getInt("quality_threshold"), rs.getInt("score_gap_threshold"),
                        rs.getString("scoring_version"))).single();
        int winnerScore = outcome.scores().stream()
                .filter(score -> score.candidateId().equals(outcome.winner().candidateId()))
                .mapToInt(JudgeCandidateScore::total).findFirst().orElse(0);
        int updated = db.sql("update judge_result set selected_candidate_id=:selected,scores_json=:scores,score_gap=:gap," +
                        "needs_human_review=:review,fallback_reason=:fallback,judge_attempt_id=:attempt,winner_score=:winner," +
                        "candidate_set_hash=:setHash where evaluation_batch_id=:batch")
                .param("batch", snapshot.evaluationBatchId()).param("selected", outcome.winner().candidateId())
                .param("scores", write(outcome.scores())).param("gap", outcome.scoreGap())
                .param("review", outcome.needsHumanReview()).param("fallback", outcome.fallbackReason())
                .param("attempt", judgeAttemptId).param("winner", winnerScore).param("setHash", candidateSetHash).update();
        if (updated == 0) {
            db.sql("insert into judge_result(id,evaluation_batch_id,selected_candidate_id,scores_json,score_gap," +
                            "needs_human_review,fallback_reason,judge_attempt_id,winner_score,quality_threshold," +
                            "score_gap_threshold,scoring_version,candidate_set_hash,created_at) values(:id,:batch,:selected," +
                            ":scores,:gap,:review,:fallback,:attempt,:winner,:quality,:gapThreshold,:scoring,:setHash," +
                            "current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("batch", snapshot.evaluationBatchId())
                    .param("selected", outcome.winner().candidateId()).param("scores", write(outcome.scores()))
                    .param("gap", outcome.scoreGap()).param("review", outcome.needsHumanReview())
                    .param("fallback", outcome.fallbackReason()).param("attempt", judgeAttemptId)
                    .param("winner", winnerScore).param("quality", thresholds.qualityThreshold())
                    .param("gapThreshold", thresholds.scoreGapThreshold())
                    .param("scoring", thresholds.scoringVersion()).param("setHash", candidateSetHash).update();
        }
        db.sql("update evaluation_batch set status=:status,selected_candidate_id=:selected,winner_score=:winner," +
                        "score_gap=:gap,needs_human_review=:review,version=version+1,updated_at=current_timestamp where id=:id")
                .param("status", outcome.needsHumanReview() ? "HUMAN_REVIEW_REQUIRED" : "COMPLETED")
                .param("selected", outcome.winner().candidateId()).param("winner", winnerScore)
                .param("gap", outcome.scoreGap()).param("review", outcome.needsHumanReview())
                .param("id", snapshot.evaluationBatchId()).update();
        enqueueKnowledgeCandidate(snapshot, outcome, traceId);
    }

    /**
     * 只有稳定、可复用、有权威证据且无需人工复核的答案进入审核 Outbox。
     *
     * <p>订单、物流、余额、地址、手机号、具体金额等动态个案即使得分很高也只能 REJECTED，不能
     * 自动沉淀成公共知识。符合条件的答案也只是进入 PENDING_REVIEW，不会直接发布；知识候选和
     * Outbox 事件在同一事务中写入，避免“数据库已有候选但消息永久丢失”。</p>
     */
    private void enqueueKnowledgeCandidate(EvaluationContextSnapshot snapshot, JudgeOutcome outcome, String traceId) {
        String answer = outcome.winner().answer();
        String normalizedQuestion = normalizeQuestion(snapshot.originalQuestion());
        boolean dynamic = snapshot.intent().name().equals("IN_SALE") || containsDynamic(normalizedQuestion + " " + answer);
        boolean eligible = !dynamic && !outcome.needsHumanReview()
                && outcome.winner().evidence() != null && !outcome.winner().evidence().isEmpty();
        String hash = sha256(snapshot.tenantId() + "|" + normalizedQuestion + "|" + answer.strip());
        db.sql("select id from evaluation_batch where tenant_id=:tenant order by created_at,id limit 1 for update")
                .param("tenant", snapshot.tenantId()).query(String.class).single();
        int duplicate = db.sql("select count(*) from knowledge_candidate where tenant_id=:tenant and content_hash=:hash")
                .param("tenant", snapshot.tenantId()).param("hash", hash).query(Integer.class).single();
        if (duplicate > 0) return;
        String candidateId = "KC-" + UUID.randomUUID().toString().substring(0, 12);
        String status = eligible ? "PENDING_REVIEW" : "REJECTED";
        String reason = eligible ? null : "动态个案、证据不足或评审要求人工复核";
        int score = outcome.scores().stream().filter(s -> s.candidateId().equals(outcome.winner().candidateId()))
                .mapToInt(JudgeCandidateScore::total).findFirst().orElse(0);
        Map<String, String> dependencies = Map.of("knowledgeVersion", snapshot.knowledgeVersion(),
                "ruleVersion", snapshot.ruleVersion(), "promptVersion", snapshot.promptVersion());
        db.sql("insert into knowledge_candidate(id,tenant_id,normalized_question,proposed_answer,intent,evidence_json," +
                        "judge_score,source_trace_id,content_hash,status,reject_reason,knowledge_version,rule_version," +
                        "prompt_version,model_version,applicable_conditions,source_snapshot_hash,dependency_json,created_at) " +
                        "values(:id,:tenant,:question,:answer,:intent,:evidence,:score,:trace,:hash,:status,:reason," +
                        ":knowledgeVersion,:ruleVersion,:promptVersion,:modelVersion,:conditions,:snapshotHash," +
                        ":dependencies,current_timestamp)")
                .param("id", candidateId).param("tenant", snapshot.tenantId()).param("question", normalizedQuestion)
                .param("answer", answer.strip()).param("intent", snapshot.intent().name())
                .param("evidence", write(outcome.winner().evidence())).param("score", score).param("trace", traceId)
                .param("hash", hash).param("status", status).param("reason", reason)
                .param("knowledgeVersion", snapshot.knowledgeVersion()).param("ruleVersion", snapshot.ruleVersion())
                .param("promptVersion", snapshot.promptVersion())
                .param("modelVersion", String.valueOf(snapshot.businessFacts().getOrDefault("modelVersion", "unknown")))
                .param("conditions", "tenant-scoped;intent=" + snapshot.intent().name())
                .param("snapshotHash", snapshot.snapshotHash()).param("dependencies", write(dependencies)).update();
        db.sql("insert into outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json,status," +
                        "attempt_count,next_attempt_at,created_at) values(:id,:tenant,'KNOWLEDGE_CANDIDATE',:aggregate," +
                        "'KnowledgeCandidateCreated',:payload,'PENDING',0,current_timestamp,current_timestamp)")
                .param("id", UUID.randomUUID().toString()).param("tenant", snapshot.tenantId())
                .param("aggregate", candidateId).param("payload", write(Map.of("knowledgeCandidateId", candidateId,
                        "status", status, "sourceTraceId", traceId, "sourceSnapshotHash", snapshot.snapshotHash()))).update();
    }

    /** 对 candidateId:服务端总分排序后哈希，结果不受 Judge 返回顺序影响。 */
    public String candidateSetHash(List<JudgeCandidateScore> scores) {
        List<String> normalized = scores == null ? List.of() : scores.stream()
                .map(score -> score.candidateId() + ":" + score.total()).sorted().toList();
        return sha256(writeCanonical(normalized));
    }

    /** 对 candidateId:answerHash 排序后哈希，用于 Judge 请求与候选正文集合绑定。 */
    public String candidateSetHashFromDrafts(List<AgentDraft> candidates) {
        List<String> normalized = candidates.stream()
                .map(candidate -> candidate.candidateId() + ":" + sha256(candidate.answer())).sorted().toList();
        return sha256(writeCanonical(normalized));
    }

    /** 补齐稳定 snapshotId、清理问题首尾空白并把集合/业务事实转为不可变副本。 */
    private EvaluationContextSnapshot normalize(EvaluationContextSnapshot value, String hash) {
        String snapshotId = value.snapshotId() == null || value.snapshotId().isBlank()
                ? value.evaluationBatchId() + "-snapshot" : value.snapshotId();
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) deepImmutable(value.businessFacts() == null ? Map.of() : value.businessFacts());
        return new EvaluationContextSnapshot(value.evaluationBatchId(), value.tenantId(), value.userId(),
                value.conversationId(), value.runId(), value.originalQuestion() == null ? "" : value.originalQuestion().strip(),
                value.intent(), List.copyOf(value.recentMessages() == null ? List.of() : value.recentMessages()),
                List.copyOf(value.evidence() == null ? List.of() : value.evidence()), facts,
                value.knowledgeVersion(), value.ruleVersion(), value.promptVersion(),
                List.copyOf(value.riskTags() == null ? List.of() : value.riskTags()), value.createdAt(), snapshotId, hash);
    }

    /** 先经 Jackson 转为普通树结构，消除可变领域对象引用，再递归冻结。 */
    private Object deepImmutable(Object value) {
        Object plain = json.convertValue(value, Object.class);
        return freeze(plain);
    }

    /** Map 使用 TreeMap 固定键序并禁止修改，List 递归复制后禁止修改。 */
    private Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), freeze(item)));
            return Collections.unmodifiableMap(sorted);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(freeze(item)));
            return Collections.unmodifiableList(result);
        }
        // businessFacts is deserialized as Map<String, Object>. Jackson can therefore restore the same JSON
        // number as Integer, Double or BigDecimal. Canonicalizing every Number prevents a harmless type change
        // during database recovery from changing the snapshot hash (order amounts are the common case).
        if (value instanceof Number number) return canonicalNumber(number);
        return value;
    }

    private BigDecimal canonicalNumber(Number value) {
        BigDecimal decimal;
        if (value instanceof BigDecimal number) decimal = number;
        else if (value instanceof BigInteger number) decimal = new BigDecimal(number);
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            decimal = BigDecimal.valueOf(value.longValue());
        else if (value instanceof Float || value instanceof Double) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("评审快照不能包含非有限数字");
            decimal = BigDecimal.valueOf(number);
        } else {
            try { decimal = new BigDecimal(value.toString()); }
            catch (NumberFormatException error) {
                throw new IllegalArgumentException("评审快照包含无法规范化的数字", error);
            }
        }
        if (decimal.signum() == 0) return BigDecimal.ZERO;
        BigDecimal normalized = decimal.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    /** 仅用于知识候选去重，不改变评审快照里的原始问题。 */
    private String normalizeQuestion(String value) {
        if (value == null) return "";
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 保守识别不能沉淀为可复用知识的订单、物流、身份、余额、地址和金额特征。 */
    private boolean containsDynamic(String value) {
        return value != null && value.matches(".*(?:(?:OD|BT|TK)[A-Za-z0-9-]+|订单号|物流轨迹|余额|退款成功|" +
                "补偿金额|手机号|地址|\\d+(?:\\.\\d{1,2})?元).*" );
    }

    /** attempt 编号和最终提交前使用数据库行锁，跨进程串行化同一批次的关键写入。 */
    private void lockBatch(String batchId) {
        db.sql("select id from evaluation_batch where id=:id for update").param("id", batchId)
                .query(String.class).optional().orElseThrow(() -> new IllegalStateException("评审批次不存在: " + batchId));
    }

    private List<String> readStrings(String value) throws JsonProcessingException {
        if (value == null || value.isBlank()) return List.of();
        return json.readValue(value, new TypeReference<List<String>>() {});
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("评审审计数据序列化失败", error); }
    }

    /** 只用于哈希材料；Map 键排序保证相同语义对象产生相同 JSON。 */
    private String writeCanonical(Object value) {
        try { return canonicalJson.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("评审哈希数据序列化失败", error); }
    }

    /** 所有审计哈希统一使用 UTF-8 SHA-256，避免平台默认字符集造成跨环境差异。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** 限制外部原因/异常文本长度；结构化类型和哈希字段不受截断影响。 */
    private String truncate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record BatchIdentity(String tenantId, String userId, String runId) {}
    private record BatchVersion(String status, int version) {}
    private record BatchThresholds(int qualityThreshold, int scoreGapThreshold, String scoringVersion) {}
    public record CandidateAttempt(String id, int attemptNo, String candidateRunId, CandidateProfile profile) {}
    public record JudgeAttempt(String id, int attemptNo, String candidateSetHash) {}
    public record RecoveredCandidate(AgentDraft draft, String candidateRunId, String attemptId,
                                     CandidateProfile profile, int promptTokens, int completionTokens,
                                     long elapsedMs) {}
}
