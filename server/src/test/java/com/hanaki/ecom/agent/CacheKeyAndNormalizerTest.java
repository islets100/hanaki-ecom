package com.hanaki.ecom.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheKeyAndNormalizerTest {
    @Test
    void normalizationIsIdempotentAndPreservesBusinessMeaning() {
        String source = "  不可以 退 iPhone１６，金额 ￥1,299.00，明天 10:30 到上海，状态=待发货  ";
        String once = QueryNormalizer.normalize(source);

        assertThat(QueryNormalizer.normalize(once)).isEqualTo(once);
        assertThat(once).contains("不可以", "iphone16", "1,299.00", "10:30", "上海", "待发货");
    }

    @Test
    void keyIsStableContainsNoRawIdentityAndSeparatesTenantPermissionAndUser() {
        CachePolicy tenantPolicy = CachePolicy.retrieval();
        CacheContext first = CacheContext.tenant("tenant-secret-a", "knowledge:read:role-a",
                "kb-v7", "embedding-v3", "trace-never-in-key-1");
        CacheContext sameExceptTrace = CacheContext.tenant("tenant-secret-a", "knowledge:read:role-a",
                "kb-v7", "embedding-v3", "trace-never-in-key-2");
        String key = CacheKeyBuilder.build(tenantPolicy, first, "订单 20260802 的退款规则", "source-v7");

        assertThat(CacheKeyBuilder.build(tenantPolicy, sameExceptTrace,
                "订单 20260802 的退款规则", "source-v7")).isEqualTo(key);
        assertThat(key).doesNotContain("tenant-secret-a", "订单", "20260802", "trace-never-in-key");
        assertThat(CacheKeyBuilder.build(tenantPolicy,
                CacheContext.tenant("tenant-secret-b", "knowledge:read:role-a", "kb-v7", "embedding-v3", ""),
                "订单 20260802 的退款规则", "source-v7")).isNotEqualTo(key);
        assertThat(CacheKeyBuilder.build(tenantPolicy,
                CacheContext.tenant("tenant-secret-a", "knowledge:read:role-b", "kb-v7", "embedding-v3", ""),
                "订单 20260802 的退款规则", "source-v7")).isNotEqualTo(key);

        CachePolicy runPolicy = CachePolicy.runScopedTool();
        CacheContext userOne = runContext("user-one", "run-a");
        CacheContext userTwo = runContext("user-two", "run-a");
        assertThat(CacheKeyBuilder.build(runPolicy, userOne, "recent-orders", "orders-v1"))
                .isNotEqualTo(CacheKeyBuilder.build(runPolicy, userTwo, "recent-orders", "orders-v1"));
        assertThatThrownBy(() -> CacheKeyBuilder.build(runPolicy,
                CacheContext.tenant("tenant", "order:read", "", "", ""), "recent-orders", "orders-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CacheContext runContext(String user, String run) {
        return new CacheContext("tenant", user, "conversation", run, "IN_SALE", "tool",
                "order:read", "orders-v1", "prompt-v1", "model-v1", "tools-v1", 0L, "trace");
    }
}
