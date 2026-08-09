package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolProjectionAndEvidencePackTest {
    @Test
    void orderProjectionRetainsCriticalFactsButDropsIsolationFields() throws Exception {
        OrderSummary order = new OrderSummary("ORDER-1", "****0001", "tenant-secret", "user-secret",
                "product-1", "相机", "黑色", new BigDecimal("1299.90"), "PAID", "BALANCE_PAID",
                "WAITING_PICKUP", "官方店", Instant.parse("2026-08-03T01:00:00Z"),
                Instant.parse("2026-08-05T01:00:00Z"), Instant.parse("2026-08-02T01:00:00Z"));

        var projected = ToolResultProjector.orders(List.of(order));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(projected);

        assertThat(projected.totalCount()).isEqualTo(1);
        assertThat(projected.returnedCount()).isEqualTo(1);
        assertThat(projected.truncated()).isFalse();
        assertThat(json).contains("ORDER-1", "1299.90", "BALANCE_PAID", "WAITING_PICKUP");
        assertThat(json).doesNotContain("tenant-secret", "user-secret", "product-1");
    }

    @Test
    void evidencePackDeduplicatesSameContentWithoutRewritingConditionsOrExceptions() {
        KnowledgeDoc high = new KnowledgeDoc("K-1", "tenant", "AFTER_SALE", "退款规则",
                "仅当七天内且商品完好才可申请，但是质量问题除外。", "v3", .9);
        KnowledgeDoc duplicate = new KnowledgeDoc("K-2", "tenant", "AFTER_SALE", "重复切片",
                "仅当七天内且商品完好才可申请，但是质量问题除外。", "v3", .8);

        List<KnowledgeDoc> pack = EvidencePackBuilder.build(List.of(duplicate, high), 8);

        assertThat(pack).singleElement().satisfies(doc -> {
            assertThat(doc.id()).isEqualTo("K-1");
            assertThat(doc.content()).contains("仅当", "但是", "除外");
        });
    }
}
