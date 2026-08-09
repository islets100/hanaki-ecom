package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.ToolOperationStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolOperationLedgerTest {
    private ToolOperationLedger ledger;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:operation-ledger-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        ledger = new ToolOperationLedger(JdbcClient.create(source));
    }

    @Test
    void sameBusinessOperationCannotChangeRequestDigest() {
        ledger.begin("tenant-a", "BT-1", "REFUND", "digest-a");

        assertThatThrownBy(() -> ledger.begin("tenant-a", "BT-1", "REFUND", "digest-b"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("参数摘要发生变化");
    }

    @Test
    void unknownOperationRequiresExplicitDownstreamReconciliationBeforeRetry() {
        var operation = ledger.begin("tenant-a", "BT-2", "REFUND", "digest-a");
        ledger.unknown(operation.operationId(), new RuntimeException("timeout"));

        assertThat(ledger.find("tenant-a", "BT-2", "REFUND").status())
                .isEqualTo(ToolOperationStatus.UNKNOWN);
        assertThatThrownBy(() -> ledger.allowRetryAfterReconciliation(operation.operationId(), false))
                .isInstanceOf(IllegalArgumentException.class);

        ledger.allowRetryAfterReconciliation(operation.operationId(), true);
        assertThat(ledger.begin("tenant-a", "BT-2", "REFUND", "digest-a").status())
                .isEqualTo(ToolOperationStatus.EXECUTING);
    }
}
