package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.ChatRequest;
import com.hanaki.ecom.domain.Domain.ChatResponse;
import com.hanaki.ecom.domain.Domain.ExecutionStatus;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.RiskLevel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestExecutionStoreTest {
    private JdbcClient db;
    private RequestExecutionStore store;
    private ChatRequest request;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:request-dedup-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        store = new RequestExecutionStore(db, new ObjectMapper().findAndRegisterModules(), 120, 2);
        request = new ChatRequest("tenant", "user", "conversation", "message-1", "同一条消息");
    }

    @Test
    void concurrentAcquireHasOneOwnerAndDuplicateReceivesPersistedResponse() {
        var owner = store.acquire(request, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        var duplicate = store.acquire(request, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(owner.acquired()).isTrue();
        assertThat(duplicate.acquired()).isFalse();
        assertThat(duplicate.runId()).isEqualTo(owner.runId());

        ChatResponse response = new ChatResponse(owner.runId(), owner.subRunId(), owner.traceId(), Intent.PRE_SALE,
                .95, RiskLevel.LOW, "完成", List.of(), List.of(), ExecutionStatus.COMPLETED,
                null, null, false, "C1", 20);
        store.complete(request, owner, response);

        assertThat(store.awaitCompletion(request).answer()).isEqualTo("完成");
    }

    @Test
    void expiredLeaseCanBeRecoveredWithoutChangingRunIdentity() {
        var first = store.acquire(request, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        db.sql("update agent_request_dedup set lease_expires_at=dateadd('SECOND',-1,current_timestamp) " +
                "where message_id='message-1'").update();

        var recovered = store.acquire(request, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        assertThat(recovered.acquired()).isTrue();
        assertThat(recovered.runId()).isEqualTo(first.runId());
        assertThat(recovered.leaseOwner()).isNotEqualTo(first.leaseOwner());
    }
}
