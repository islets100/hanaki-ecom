package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.observability.AgentTelemetryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeOutboxWorkerTest {
    private JdbcClient db;
    private KnowledgeOutboxWorker worker;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:outbox-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        AgentTelemetryService telemetry = mock(AgentTelemetryService.class);
        when(telemetry.observeLinkedTrace(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        worker = new KnowledgeOutboxWorker(db, new ObjectMapper(), mock(MemoryContextService.class),
                mock(VersionedAgentCache.class), telemetry, 20, 2, 10,
                new SimpleMeterRegistry(), new DataSourceTransactionManager(source));
    }

    @Test
    void publishesKnownEventAndTransitionsKnowledgeCandidate() {
        db.sql("insert into knowledge_candidate(id,tenant_id,normalized_question,proposed_answer,intent,evidence_json," +
                        "judge_score,source_trace_id,content_hash,status,created_at) values('KC1','tenant','问题','答案'," +
                        "'PRE_SALE','[]',90,'trace','hash-1','PENDING_REVIEW',current_timestamp)").update();
        insertEvent("E1", "KnowledgeCandidateCreated", "KC1", 0, "PENDING", Instant.now());

        worker.poll();

        assertThat(status("E1")).isEqualTo("PUBLISHED");
        assertThat(db.sql("select status from knowledge_candidate where id='KC1'")
                .query(String.class).single()).isEqualTo("READY_FOR_REVIEW");
    }

    @Test
    void recoversExpiredLeaseAndDeadLettersUnsupportedEventAfterBoundedRetries() {
        insertEvent("E2", "UnsupportedEvent", "X1", 0, "PROCESSING", Instant.now().minusSeconds(30));

        worker.poll();
        assertThat(status("E2")).isEqualTo("RETRY");
        db.sql("update outbox_event set next_attempt_at=current_timestamp where id='E2'").update();
        worker.poll();

        assertThat(status("E2")).isEqualTo("DEAD_LETTER");
        assertThat(db.sql("select attempt_count from outbox_event where id='E2'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    private void insertEvent(String id, String type, String aggregate, int attempts, String status, Instant claimed) {
        db.sql("insert into outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json,status," +
                        "attempt_count,next_attempt_at,claimed_at,worker_id,created_at) values(:id,'tenant','TEST',:aggregate," +
                        ":type,:payload,:status,:attempt,current_timestamp,:claimed,'old-worker',current_timestamp)")
                .param("id", id).param("aggregate", aggregate).param("type", type)
                .param("payload", write(Map.of("eventId", UUID.randomUUID().toString())))
                .param("status", status).param("attempt", attempts).param("claimed", Timestamp.from(claimed)).update();
    }

    private String status(String id) {
        return db.sql("select status from outbox_event where id=:id").param("id", id)
                .query(String.class).single();
    }

    private String write(Object value) {
        try { return new ObjectMapper().writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
