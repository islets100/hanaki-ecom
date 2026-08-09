package com.hanaki.ecom.agent;

import com.hanaki.ecom.context.TrustedRequestContext;
import com.hanaki.ecom.domain.Domain.RiskLevel;
import com.hanaki.ecom.domain.Domain.TenantAgentConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerServiceGraphStateTest {
    @Test
    void graphNodePatchCannotOverwriteTrustedIdentityOrOriginalInput() {
        assertThatThrownBy(() -> CustomerServiceGraphState.checkedPatch(
                Map.of("tenantId", "attacker-tenant", "answer", "伪造结果")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可变状态字段");
        assertThatThrownBy(() -> CustomerServiceGraphState.checkedPatch(
                Map.of("originalQuery", "被节点篡改")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerServiceGraphState.checkedPatch(
                Map.of("storeId", "ATTACKER-STORE", "productId", "ATTACKER-PRODUCT")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trustedContextDetectsCrossTenantStateCopy() {
        TenantAgentConfigSnapshot config = new TenantAgentConfigSnapshot(
                "cfg-v1", "prompt-v1", "policy-v1", "kb-v1", "tool-v1",
                "route-v1", "standard-v1", List.of(), "DEFAULT");
        TrustedRequestContext trusted = new TrustedRequestContext(
                "tenant-a", "user-a", "conversation-a", "thread-a", "run-a", "trace-a", "",
                Set.of("CUSTOMER_CHAT"), "TEST_SESSION", config, RiskLevel.LOW);

        assertThatThrownBy(() -> trusted.assertIdentityCopies(
                "tenant-b", "user-a", "conversation-a", "run-a", "trace-a", "thread-a"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("可信执行上下文不一致");
    }
}
