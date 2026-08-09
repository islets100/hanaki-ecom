package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.store.EcommerceStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScopedCommerceToolsOrderScopeTest {
    @Test
    void logisticsRejectsOrderIdNotReturnedByCurrentProductQuery() {
        EcommerceStore store = mock(EcommerceStore.class);
        ToolGateway gateway = mock(ToolGateway.class);
        OrderSummary currentProductOrder = new OrderSummary("OD-CURRENT", "••••RENT", "tenant", "user", "P2",
                "静听 Pro", "标准款", new BigDecimal("899"), "PAID", "BALANCE_PAID", "待发货",
                "测试店铺", null, null, Instant.now());
        when(store.recentOrdersForProduct("tenant", "user", "STORE-1", "P2"))
                .thenReturn(List.of(currentProductOrder));

        ScopedCommerceTools tools = new ScopedCommerceTools(Intent.IN_SALE, "tenant", "user", store, gateway,
                null, "trace", OrderQueryScope.currentProduct("STORE-1", "P2", "静听 Pro"));

        assertThat(tools.recentOrders()).extracting(OrderSummary::id).containsExactly("OD-CURRENT");
        assertThat(tools.queryLogistics("OD-OTHER")).isEmpty();
        verify(store).recentOrdersForProduct("tenant", "user", "STORE-1", "P2");
        verify(store, never()).logistics("tenant", "user", "OD-OTHER");
    }
}
