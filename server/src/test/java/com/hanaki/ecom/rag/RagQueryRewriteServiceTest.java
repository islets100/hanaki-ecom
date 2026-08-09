package com.hanaki.ecom.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryRewriteServiceTest {
    private final RagQueryRewriteService service = new RagQueryRewriteService("rewrite-test-v1");

    @Test
    void shouldRestoreProtectedEntitiesButRejectControlFields() {
        var result = service.build("SKU-9988 能否退货", "这个商品支持退款吗", List.of(),
                Map.of("sku", "SKU-9988", "orderId", "ORD-1001",
                        "tenantId", "other-tenant", "index", "secret-index"),
                "qwen-test", false);

        // 模型改写即使漏掉 SKU/订单号，服务端也会补回；tenant/index 不在实体白名单，必须丢弃。
        assertThat(result.lexicalQuery()).contains("SKU-9988", "ORD-1001", "退换货");
        assertThat(result.semanticQuery()).contains("SKU-9988", "ORD-1001");
        assertThat(result.protectedEntities()).containsOnlyKeys("sku", "orderId");
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    void shouldRequireClarificationForUnresolvedProductReference() {
        var result = service.build("这个能退吗", "这个商品能否退货", List.of(), Map.of(),
                "qwen-test", false);

        assertThat(result.clarificationRequired()).isTrue();
        assertThat(result.clarificationReason()).contains("指代");
        assertThat(result.confidence()).isLessThan(0.5d);
    }

    @Test
    void shouldFallbackToOriginalQuestionWhenModelFails() {
        var result = service.build("型号 A-10 的保修多久", "", List.of(),
                Map.of("model", "A-10"), "qwen-test", true);

        assertThat(result.standaloneQuestion()).isEqualTo("型号 A-10 的保修多久");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.modelVersion()).contains("qwen-test", "rewrite-test-v1");
    }
}
