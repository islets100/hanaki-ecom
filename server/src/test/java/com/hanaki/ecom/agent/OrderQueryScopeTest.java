package com.hanaki.ecom.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderQueryScopeTest {
    @Test
    void storeProductConversationDefaultsToCurrentProduct() {
        OrderQueryScope scope = OrderQueryScope.resolve(
                "STORE", "STORE-1", "P2", "静听 Pro", "什么时候可以发货？");

        assertThat(scope.mode()).isEqualTo(OrderQueryScope.Mode.CURRENT_PRODUCT);
        assertThat(scope.storeId()).isEqualTo("STORE-1");
        assertThat(scope.productId()).isEqualTo("P2");
    }

    @Test
    void explicitWholeStoreQuestionExpandsOnlyToCurrentStore() {
        OrderQueryScope scope = OrderQueryScope.resolve(
                "STORE", "STORE-1", "P2", "静听 Pro", "查询我在这家店的所有订单");

        assertThat(scope.mode()).isEqualTo(OrderQueryScope.Mode.CURRENT_STORE);
        assertThat(scope.storeId()).isEqualTo("STORE-1");
        assertThat(scope.productId()).isBlank();
    }

    @Test
    void officialConversationKeepsCurrentUserOrderScope() {
        OrderQueryScope scope = OrderQueryScope.resolve(
                "OFFICIAL", "", "", "", "查询我的最近订单");

        assertThat(scope.mode()).isEqualTo(OrderQueryScope.Mode.CURRENT_USER);
    }
}
