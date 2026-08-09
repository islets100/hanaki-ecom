package com.hanaki.ecom.store;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class EcommerceStoreIdempotencyTest {
    private JdbcClient db;
    private EcommerceStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:store-idem-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), dataSource);
        db = JdbcClient.create(dataSource);
        db.sql("insert into app_account(id,tenant_id,username,password_hash,display_name,role,enabled,created_at) " +
                "values('user','tenant','user_01','hash','用户','CUSTOMER',true,current_timestamp)").update();
        store = new EcommerceStore(db);
    }

    @Test
    void retryWithSameKeyAndSecondActiveRequestReuseOneBusinessTask() {
        String first = store.createBusinessTask("tenant", "user", "OD-1", "REFUND",
                "WAITING_CONFIRMATION", "rule-v1", "request-key-1");
        String replay = store.createBusinessTask("tenant", "user", "OD-1", "REFUND",
                "WAITING_CONFIRMATION", "rule-v1", "request-key-1");
        String secondButtonClick = store.createBusinessTask("tenant", "user", "OD-1", "REFUND",
                "WAITING_CONFIRMATION", "rule-v1", "request-key-2");

        assertThat(replay).isEqualTo(first);
        assertThat(secondButtonClick).isEqualTo(first);
        assertThat(db.sql("select count(*) from business_task").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void messageAndCheckpointRecoveryAreIdempotent() {
        store.saveMessage("tenant", "user", "conversation", "run-1", "USER", "第一次内容");
        store.saveMessage("tenant", "user", "conversation", "run-1", "USER", "恢复时重复内容");
        store.saveCheckpoint("tenant", "conversation", "run-1", "final", "{\"step\":1}");
        store.saveCheckpoint("tenant", "conversation", "run-1", "final", "{\"step\":2}");

        assertThat(db.sql("select count(*) from conversation_message").query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from agent_checkpoint").query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select state_json from agent_checkpoint").query(String.class).single())
                .isEqualTo("{\"step\":2}");
    }
}
