package com.hanaki.ecom.observability;

import com.hanaki.ecom.observability.ObservabilityModels.SpanWrite;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityStoreTest {
    private ObservabilityStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:trace-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), dataSource);
        store = new ObservabilityStore(JdbcClient.create(dataSource));
    }

    @Test
    void replaysTreeAndAggregatesTokenCostWithoutCrossUserLeakage() {
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        store.save(span("root", null, "agent.run", "SERVER", 0, 0, BigDecimal.ZERO, start));
        store.save(span("model", "root", "candidate.run.1", "MODEL", 120, 40,
                new BigDecimal("0.00120000"), start.plusMillis(10)));

        var replay = store.replay("tenant", "user", "trace-1").orElseThrow();
        assertThat(replay.spans()).extracting(ObservabilityModels.TraceSpanView::spanId)
                .containsExactly("root", "model");
        assertThat(replay.summary().totalTokens()).isEqualTo(160);
        assertThat(replay.summary().costCny()).isEqualByComparingTo("0.0012");
        assertThat(store.overview("tenant", "user").modelCalls()).isEqualTo(1);
        assertThat(store.replay("tenant", "other-user", "trace-1")).isEmpty();
    }

    private SpanWrite span(String spanId, String parent, String name, String kind,
                           int prompt, int completion, BigDecimal cost, Instant start) {
        return new SpanWrite("id-" + spanId, "trace-1", spanId, parent, "tenant", "user",
                "conversation", "run", name, kind, "COMPLETED", 20, "ok", "{}", "{}", "{}",
                null, null, kind.equals("MODEL") ? "qwen-plus" : null,
                prompt, completion, prompt + completion, cost, start, start.plusMillis(20));
    }
}
