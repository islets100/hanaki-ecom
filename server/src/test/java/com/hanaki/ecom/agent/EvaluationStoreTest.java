package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.CandidateProfile;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.Intent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationStoreTest {
    private JdbcClient db;
    private EvaluationStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:evaluation-store-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        store = new EvaluationStore(db, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void freezesCanonicalSnapshotAndRejectsTampering() {
        EvaluationContextSnapshot draft = snapshot("B1", "tenant", "user", "R1", "原始问题");
        store.createBatch(draft, "trace");

        EvaluationContextSnapshot sealed = store.freezeSnapshot(draft);

        assertThat(sealed.snapshotHash()).hasSize(64);
        assertThat(store.loadSnapshot("B1")).contains(sealed);
        assertThatThrownBy(() -> sealed.businessFacts().put("tampered", true))
                .isInstanceOf(UnsupportedOperationException.class);
        EvaluationContextSnapshot tampered = new EvaluationContextSnapshot(sealed.evaluationBatchId(),
                sealed.tenantId(), sealed.userId(), sealed.conversationId(), sealed.runId(), "被篡改的问题",
                sealed.intent(), sealed.recentMessages(), sealed.evidence(), sealed.businessFacts(),
                sealed.knowledgeVersion(), sealed.ruleVersion(), sealed.promptVersion(), sealed.riskTags(),
                sealed.createdAt(), sealed.snapshotId(), sealed.snapshotHash());
        assertThatThrownBy(() -> store.verifySnapshot(tampered))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("完整性");
    }

    @Test
    void keepsEveryPhysicalAttemptAndEnforcesBoundedRetries() {
        EvaluationContextSnapshot draft = snapshot("B2", "tenant", "user", "R2", "问题");
        store.createBatch(draft, "trace");
        EvaluationContextSnapshot sealed = store.freezeSnapshot(draft);

        var first = store.startCandidateAttempt(sealed, 1, CandidateProfile.FACT_AND_EVIDENCE, 2).orElseThrow();
        store.failCandidateAttempt(first.id(), new IllegalStateException("first"), 12);
        var second = store.startCandidateAttempt(sealed, 1, CandidateProfile.FACT_AND_EVIDENCE, 2).orElseThrow();
        store.failCandidateAttempt(second.id(), new IllegalStateException("second"), 18);

        assertThat(store.startCandidateAttempt(sealed, 1, CandidateProfile.FACT_AND_EVIDENCE, 2)).isEmpty();
        assertThat(db.sql("select attempt_no from candidate_attempt where evaluation_batch_id='B2' order by attempt_no")
                .query(Integer.class).list()).containsExactly(1, 2);

        var judge1 = store.startJudgeAttempt(sealed, "candidate-set", 2, "judge-model").orElseThrow();
        store.failJudgeAttempt(judge1.id(), new IllegalStateException("judge-1"), 5);
        var judge2 = store.startJudgeAttempt(sealed, "candidate-set", 2, "judge-model").orElseThrow();
        store.failJudgeAttempt(judge2.id(), new IllegalStateException("judge-2"), 6);
        assertThat(store.startJudgeAttempt(sealed, "candidate-set", 2, "judge-model")).isEmpty();
        assertThat(db.sql("select count(*) from judge_attempt where evaluation_batch_id='B2'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void rejectsIllegalStateJumpAndCrossTenantBatchReuse() {
        EvaluationContextSnapshot first = snapshot("B3", "tenant-a", "user", "R3", "问题");
        store.createBatch(first, "trace");

        assertThatThrownBy(() -> store.advanceStatus("B3", "CANDIDATES_RUNNING", "SNAPSHOT_READY"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("非法评审状态转换");
        assertThatThrownBy(() -> store.createBatch(snapshot("B3", "tenant-b", "user", "R3", "问题"), "trace"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void preservesSnapshotHashWhenOrderAmountIsRestoredAsAnUntypedNumber() {
        EvaluationContextSnapshot base = snapshot("B4", "tenant", "user", "R4", "这个商品如何售后");
        EvaluationContextSnapshot draft = new EvaluationContextSnapshot(base.evaluationBatchId(), base.tenantId(),
                base.userId(), base.conversationId(), base.runId(), base.originalQuestion(), Intent.AFTER_SALE,
                base.recentMessages(), base.evidence(), Map.of(
                        "rewrittenQuery", base.originalQuestion(),
                        "recentOrders", List.of(Map.of(
                                "orderId", "OD-1001",
                                "productId", "P1001",
                                "amount", new BigDecimal("1299.00")))),
                base.knowledgeVersion(), base.ruleVersion(), base.promptVersion(), base.riskTags(), base.createdAt());
        store.createBatch(draft, "trace");

        EvaluationContextSnapshot sealed = store.freezeSnapshot(draft);
        EvaluationContextSnapshot restored = store.loadSnapshot("B4").orElseThrow();

        assertThat(store.snapshotHash(restored)).isEqualTo(sealed.snapshotHash());
        assertThatCode(() -> store.verifySnapshot(restored)).doesNotThrowAnyException();
        assertThat(store.freezeSnapshot(draft).snapshotHash()).isEqualTo(sealed.snapshotHash());
    }

    private EvaluationContextSnapshot snapshot(String batch, String tenant, String user, String run, String question) {
        return new EvaluationContextSnapshot(batch, tenant, user, "conversation", run, question,
                Intent.PRE_SALE, List.of("用户: 上一轮"), List.of(),
                Map.of("rewrittenQuery", question, "nested", Map.of("b", 2, "a", 1)),
                "knowledge-v1", "rule-v1", "prompt-v1", List.of("LOW"), Instant.parse("2026-08-01T00:00:00Z"));
    }
}
