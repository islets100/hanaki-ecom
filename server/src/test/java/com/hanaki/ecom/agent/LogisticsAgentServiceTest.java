package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentResult;
import com.hanaki.ecom.domain.Domain.LogisticsEvent;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.store.EcommerceStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LogisticsAgentServiceTest {
    @Test
    void queriesTrustedOrdersThenQueriesLogisticsWithTheReturnedOrderId() {
        EcommerceStore store = mock(EcommerceStore.class);
        ToolGateway gateway = mock(ToolGateway.class);
        OrderSummary order = new OrderSummary("OD-100", "••••0100", "tenant", "user", "P1",
                "测试商品", "标准款", new BigDecimal("30"), "PROCESSING", "BALANCE_PAID", "待发货",
                "测试店铺", Instant.parse("2026-07-31T10:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"), Instant.parse("2026-07-31T01:00:00Z"));
        when(store.recentOrders("tenant", "user")).thenReturn(List.of(order));
        when(store.logistics("tenant", "user", "OD-100")).thenReturn(List.of(
                new LogisticsEvent("2026-07-31T01:00:00Z", "花木商城", "余额支付成功，等待发货")));

        AgentResult result = new LogisticsAgentService(store, gateway, mock(AgentTelemetryService.class))
                .answer("tenant", "user", "查询订单 OD-100 的物流");

        verify(store).recentOrders("tenant", "user");
        verify(store).logistics("tenant", "user", "OD-100");
        assertThat(result.answer()).contains("OD-100", "测试商品", "计划发货时间", "预计到达时间", "余额支付成功");
        assertThat(result.toolResults()).hasSize(2)
                .anyMatch(value -> value.startsWith("toolResultRef=recentOrders#"))
                .anyMatch(value -> value.startsWith("toolResultRef=queryLogistics#"));
    }

    @Test
    void reportsNoOrdersInsteadOfReturningAWaitingPlaceholder() {
        EcommerceStore store = mock(EcommerceStore.class);
        ToolGateway gateway = mock(ToolGateway.class);
        when(store.recentOrders("tenant", "user")).thenReturn(List.of());

        AgentResult result = new LogisticsAgentService(store, gateway, mock(AgentTelemetryService.class))
                .answer("tenant", "user", "我的快递到哪了");

        assertThat(result.answer()).contains("还没有订单").doesNotContain("请稍候", "正在查询");
        verify(store).recentOrders("tenant", "user");
        verifyNoMoreInteractions(store);
    }

    @Test
    void storeProductConversationDoesNotFallBackToAnotherProductOrder() {
        EcommerceStore store = mock(EcommerceStore.class);
        ToolGateway gateway = mock(ToolGateway.class);
        when(store.recentOrdersForProduct("tenant", "user", "STORE-1", "P2")).thenReturn(List.of());

        AgentResult result = new LogisticsAgentService(store, gateway, mock(AgentTelemetryService.class))
                .answer("tenant", "user", "什么时候可以发货？",
                        OrderQueryScope.currentProduct("STORE-1", "P2", "静听 Pro"));

        assertThat(result.answer()).contains("未查询到", "当前商品", "静听 Pro")
                .doesNotContain("其他商品");
        verify(store).recentOrdersForProduct("tenant", "user", "STORE-1", "P2");
        verifyNoMoreInteractions(store);
    }

    @Test
    void recognizesAContinuationUsingRecentLogisticsContext() {
        LogisticsAgentService service = new LogisticsAgentService(mock(EcommerceStore.class), mock(ToolGateway.class),
                mock(AgentTelemetryService.class));
        assertThat(service.matches("查到了吗", List.of("USER: 查询最近订单物流"))).isTrue();
    }
}
