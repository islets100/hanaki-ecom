package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.MemoryDecisionRequest;
import com.hanaki.ecom.domain.Domain.ProfileCorrectionRequest;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.memory.domain.MemoryScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MemoryContextServiceTest {
    private JdbcClient db;
    private MemoryContextService memory;
    private SessionAccount owner;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:memory-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        memory = new MemoryContextService(mock(EcommerceStore.class), db, mock(EmbeddingModel.class),
                mock(MemoryModelService.class), new ObjectMapper(), mock(AgentTelemetryService.class),
                new MemoryPersistenceStore(db), mock(ElasticsearchMemoryIndex.class),
                8, 5000, "test-embedding");
        owner = new SessionAccount("user-1", "tenant", "buyer", "用户", AccountRole.CUSTOMER, null);
        db.sql("insert into app_account(id,tenant_id,username,password_hash,display_name,role,enabled,created_at) " +
                        "values('user-1','tenant','buyer','test','用户','CUSTOMER',true,current_timestamp)").update();
        db.sql("insert into memory_candidate(id,tenant_id,user_id,source_run_id,fact_key,fact_value,memory_type," +
                        "confidence,explicitly_confirmed,ttl_days,status,created_at) values('MC1','tenant','user-1'," +
                        "'RUN1','颜色偏好','深色','PREFERENCE',0.78,false,90,'PENDING',current_timestamp)").update();
    }

    @Test
    void userApprovalCreatesConfirmedProfileAndDurableActivationEvent() {
        var result = memory.decide("MC1", new MemoryDecisionRequest("APPROVE", ""), owner);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(db.sql("select fact_value from user_profile_fact where tenant_id='tenant' and user_id='user-1' " +
                "and fact_key='颜色偏好'").query(String.class).single()).isEqualTo("深色");
        assertThat(db.sql("select count(*) from outbox_event where aggregate_id='MC1' and event_type='MemoryCandidateApproved'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anotherUserCannotReadOrDecideCandidate() {
        SessionAccount attacker = new SessionAccount("user-2", "tenant", "other", "其他用户", AccountRole.CUSTOMER, null);
        assertThat(memory.candidates(attacker, "")).isEmpty();
        assertThatThrownBy(() -> memory.decide("MC1", new MemoryDecisionRequest("APPROVE", ""), attacker))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memoryLoadsInTwoPhasesAndProfilesAreProjectedPerAgent() {
        db.sql("insert into user_profile_fact(tenant_id,user_id,fact_key,fact_value,source_run_id,confirmed," +
                "source_type,trust_level,confidence,status,version,expires_at,updated_at) values" +
                "('tenant','user-1','颜色偏好','黑色','RUN1',true,'USER_CONFIRMATION','USER_CONFIRMED',1.0," +
                "'CONFIRMED',1,dateadd('DAY',90,current_timestamp),current_timestamp)," +
                "('tenant','user-1','沟通偏好','简短中文','RUN1',true,'USER_CONFIRMATION','USER_CONFIRMED',1.0," +
                "'CONFIRMED',1,dateadd('DAY',90,current_timestamp),current_timestamp)").update();

        // 主 Agent 尚未路由，不能提前看到任何长期画像。
        assertThat(memory.build("tenant", "user-1", "conversation-1", "我要咨询商品"))
                .noneMatch(value -> value.startsWith("已确认用户画像"));

        MemoryScope preSale = new MemoryScope("tenant", "user-1", "conversation-1", "run-1", "sub-1",
                "", "PRE_SALE", "ANSWER_GENERATION", "WEB");
        assertThat(memory.buildBusinessContext(preSale, "我要咨询商品", "trace-1").legacyLines())
                .anyMatch(value -> value.contains("颜色偏好"));

        MemoryScope complaint = new MemoryScope("tenant", "user-1", "conversation-1", "run-2", "sub-2",
                "", "COMPLAINT", "ANSWER_GENERATION", "WEB");
        assertThat(memory.buildBusinessContext(complaint, "我要投诉", "trace-2").legacyLines())
                .anyMatch(value -> value.contains("沟通偏好"))
                .noneMatch(value -> value.contains("颜色偏好"));
    }

    @Test
    void mainPhaseCanLocateConversationTaskButDoesNotTreatItAsBusinessTruth() {
        MemoryScope taskScope = new MemoryScope("tenant", "user-1", "conversation-1", "run-1", "sub-1",
                "refund-task-9", "AFTER_SALE", "TASK_INDEX", "WEB");
        memory.recordConversationTask(taskScope, "WAITING_CONFIRMATION");

        assertThat(memory.buildMainContext(MemoryScope.conversation("tenant", "user-1", "conversation-1",
                        "run-2", "sub-2", "INTENT_ROUTE"), "继续刚才的退款", "trace-task").legacyLines())
                .anySatisfy(value -> {
                    assertThat(value).contains("refund-task-9", "WAITING_CONFIRMATION", "USER_CONFIRMATION");
                    // 文本中的恢复约束是 Prompt 防误用的一部分，不能只依赖 Java 注释表达。
                    assertThat(value).contains("必须查询业务系统的最新真实状态");
                });
    }

    @Test
    void userCanCorrectAndDeleteConfirmedProfileWithOutboxProjectionEvents() {
        db.sql("insert into user_profile_fact(tenant_id,user_id,fact_key,fact_value,source_run_id,confirmed," +
                "source_type,trust_level,confidence,status,version,expires_at,updated_at) values" +
                "('tenant','user-1','颜色偏好','黑色','RUN1',true,'USER_CONFIRMATION','USER_CONFIRMED',1.0," +
                "'CONFIRMED',1,dateadd('DAY',90,current_timestamp),current_timestamp)").update();

        var corrected = memory.correctProfile("颜色偏好", new ProfileCorrectionRequest("蓝色", 90), owner);
        assertThat(corrected.value()).isEqualTo("蓝色");
        assertThat(db.sql("select count(*) from user_profile_fact_history where status='SUPERSEDED'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from outbox_event where event_type in " +
                "('MemoryProfileProjectionChanged','MemoryProfileProjectionDeleted')")
                .query(Integer.class).single()).isEqualTo(2);

        assertThat(memory.deleteProfile("颜色偏好", owner)).isTrue();
        assertThat(memory.deleteProfile("颜色偏好", owner)).isFalse();
        assertThat(memory.profiles(owner)).noneMatch(value -> value.attributeCode().equals("颜色偏好"));
    }
}
