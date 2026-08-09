package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateHardValidatorTest {
    private final CandidateHardValidator validator = new CandidateHardValidator();

    @Test
    void rejectsInventedOrderAndUnverifiedWriteSuccessBeforeJudge() {
        EvaluationContextSnapshot snapshot = snapshot();
        AgentDraft draft = new AgentDraft("C1", "订单 OD-NOT-MINE 的退款已成功", List.of(),
                List.of("toolResultRef=recentOrders#abc"), true, 12, 12);

        CandidateHardValidator.Validation result = validator.validate(snapshot, draft);

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("UNVERIFIED_WRITE_SUCCESS", "ORDER_NOT_IN_FROZEN_OWNER_SNAPSHOT");
    }

    @Test
    void acceptsOwnedOrderWithRealEvidenceAndReadOnlyToolReference() {
        AgentDraft draft = new AgentDraft("C1", "订单 OD-OWNED 当前仍在运输中。",
                List.of("物流规则 v5"), List.of("toolResultRef=queryLogistics#abc;scope=current-user"),
                true, 12, 12);

        assertThat(validator.validate(snapshot(), draft).accepted()).isTrue();
    }

    @Test
    void rejectsUnmaskedPersonalDataAndWriteToolReferences() {
        AgentDraft draft = new AgentDraft("C1", "请联系 13812345678，订单 OD-OWNED 已处理。",
                List.of("物流规则 v5"), List.of("toolResultRef=submit_refund#abc;write=true"),
                true, 12, 12);

        assertThat(validator.validate(snapshot(), draft).violations())
                .contains("UNMASKED_PERSONAL_DATA", "WRITE_TOOL_USED_DURING_CANDIDATE_GENERATION");
    }

    private EvaluationContextSnapshot snapshot() {
        OrderSummary order = new OrderSummary("OD-OWNED", "••OWNED", "tenant", "user", "P1", "商品",
                "标准款", new BigDecimal("30"), "PROCESSING", "BALANCE_PAID", "运输中", "店铺",
                Instant.now(), Instant.now().plusSeconds(3600), Instant.now());
        KnowledgeDoc evidence = new KnowledgeDoc("K1", "tenant", "IN_SALE", "物流规则", "规则内容", "v5", 1);
        return new EvaluationContextSnapshot("B1", "tenant", "user", "C1", "R1", "查询物流",
                Intent.IN_SALE, List.of(), List.of(evidence), Map.of("recentOrders", List.of(order)),
                "v5", "rule-v1", "prompt-v1", List.of(), Instant.now());
    }
}
