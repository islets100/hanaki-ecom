package com.hanaki.ecom.agent;

import com.hanaki.ecom.memory.domain.MemoryScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryPersistenceStoreTest {
    private JdbcClient db;
    private MemoryPersistenceStore persistence;
    private MemoryScope scope;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:memory-persistence-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        persistence = new MemoryPersistenceStore(db);
        db.sql("insert into app_account(id,tenant_id,username,password_hash,display_name,role,enabled,created_at) " +
                "values('user-1','tenant-a','buyer','x','用户','CUSTOMER',true,current_timestamp)").update();
        scope = MemoryScope.conversation("tenant-a", "user-1", "conversation-1", "run-1", "sub-1", "SUMMARY");
    }

    @Test
    void summaryCasNeverLetsOlderRunOverwriteNewerCoverage() {
        var empty = MemoryPersistenceStore.SummarySnapshot.empty();
        assertThat(persistence.compareAndSetSummary(scope, empty, "{\"coveredMessageSeq\":10}",
                1, 10, "hash-10", "test", "v1", 100, 20)).isTrue();

        // Run B 已经推进到 12；Run A 仍拿着 version=0 的旧快照，插入必须失败。
        assertThat(persistence.compareAndSetSummary(scope, empty, "{\"coveredMessageSeq\":8}",
                1, 8, "hash-8", "test", "v1", 80, 20)).isFalse();
        var versionOne = persistence.loadSummary(scope).orElseThrow();
        assertThat(persistence.compareAndSetSummary(scope, versionOne, "{\"coveredMessageSeq\":12}",
                1, 12, "hash-12", "test", "v1", 120, 25)).isTrue();

        // 即使旧 Run 最后完成，WHERE version/covered_end_seq 已不匹配，影响行数必须为 0。
        assertThat(persistence.compareAndSetSummary(scope, versionOne, "{\"coveredMessageSeq\":11}",
                1, 11, "hash-11", "test", "v1", 110, 20)).isFalse();
        assertThat(persistence.loadSummary(scope).orElseThrow().coveredEndSeq()).isEqualTo(12);
    }

    @Test
    void profileCorrectionSupersedesOldValueAndKeepsOnlyNewValueActive() {
        Instant expiry = Instant.now().plus(90, ChronoUnit.DAYS);
        persistence.upsertProfileFact("tenant-a", "user-1", "颜色偏好", "黑色", "run-1", expiry);
        persistence.upsertProfileFact("tenant-a", "user-1", "颜色偏好", "蓝色", "run-2", expiry);

        assertThat(db.sql("select fact_value from user_profile_fact where tenant_id='tenant-a' " +
                "and user_id='user-1' and fact_key='颜色偏好'").query(String.class).single()).isEqualTo("蓝色");
        assertThat(db.sql("select fact_value || ':' || status from user_profile_fact_history " +
                "where tenant_id='tenant-a' and user_id='user-1'").query(String.class).list())
                .containsExactly("黑色:SUPERSEDED");
    }

    @Test
    void modelExtractionCannotAutoApproveCandidate() {
        assertThatThrownBy(() -> persistence.saveExtractedCandidate("candidate-1", "tenant-a", "user-1",
                "run-1", "颜色偏好", "黑色", 0.99, true, 180, "APPROVED",
                Instant.now().plus(180, ChronoUnit.DAYS), "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能进入 PENDING");
    }

    @Test
    void taskIndexIsScopedAndVersionedWithoutCopyingBusinessPayload() {
        MemoryScope taskScope = new MemoryScope("tenant-a", "user-1", "conversation-1",
                "run-1", "sub-1", "refund-task-7", "AFTER_SALE", "TASK_INDEX", "WEB");
        persistence.upsertConversationTask(taskScope, "WAITING_CONFIRMATION", "USER_CONFIRMATION",
                Instant.now().plus(7, ChronoUnit.DAYS));
        persistence.upsertConversationTask(taskScope, "COMPLETED", "",
                Instant.now().plus(30, ChronoUnit.DAYS));

        var stored = persistence.loadConversationTasks(scope);
        assertThat(stored).singleElement().satisfies(task -> {
            assertThat(task.businessTaskId()).isEqualTo("refund-task-7");
            assertThat(task.agentType()).isEqualTo("AFTER_SALE");
            assertThat(task.taskStatus()).isEqualTo("COMPLETED");
            assertThat(task.pendingActionType()).isEmpty();
            assertThat(task.version()).isEqualTo(2);
        });

        MemoryScope otherUser = MemoryScope.conversation("tenant-a", "other-user", "conversation-1",
                "run-2", "sub-2", "SUMMARY");
        assertThat(persistence.loadConversationTasks(otherUser)).isEmpty();
    }
}
