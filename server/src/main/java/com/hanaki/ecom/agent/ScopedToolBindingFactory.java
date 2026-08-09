package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.LogisticsEvent;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.domain.Domain.Product;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.store.EcommerceStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.hanaki.ecom.agent.ToolResultProjector.LogisticsView;
import com.hanaki.ecom.agent.ToolResultProjector.OrderView;
import com.hanaki.ecom.agent.ToolResultProjector.ProductView;
import com.hanaki.ecom.agent.ToolResultProjector.ProjectedList;

/**
 * 在模型调用之前按意图生成最小工具对象。权限不仅在执行时检查，其他领域的 Tool Schema
 * 根本不会发送给模型，从而同时减少误调用、Prompt Token 和越权攻击面。
 */
@Service
public final class ScopedToolBindingFactory {
    private final EcommerceStore store;
    private final ToolGateway gateway;
    private final AgentTelemetryService telemetry;

    public ScopedToolBindingFactory(EcommerceStore store, ToolGateway gateway, AgentTelemetryService telemetry) {
        this.store = store;
        this.gateway = gateway;
        this.telemetry = telemetry;
    }

    public ToolBinding create(Intent intent, String tenantId, String userId, String traceId,
                              ConcurrentHashMap<String, Object> requestCache) {
        return create(intent, tenantId, userId, traceId, OrderQueryScope.currentUser(), requestCache);
    }

    public ToolBinding create(Intent intent, String tenantId, String userId, String traceId,
                              OrderQueryScope orderScope,
                              ConcurrentHashMap<String, Object> requestCache) {
        ScopedCommerceTools delegate = new ScopedCommerceTools(intent, tenantId, userId, store, gateway,
                telemetry, traceId, orderScope, requestCache);
        Object schemas = switch (intent) {
            case PRE_SALE -> new PreSaleTools(delegate);
            case IN_SALE -> new InSaleTools(delegate);
            case AFTER_SALE -> new AfterSaleTools(delegate);
            case COMPLAINT -> new ComplaintTools(delegate);
            case HUMAN_SERVICE, UNKNOWN -> null;
        };
        List<String> exposed = switch (intent) {
            case PRE_SALE -> List.of("query_product");
            case IN_SALE -> List.of("recent_orders", "query_logistics");
            case AFTER_SALE -> List.of("recent_orders", "preview_after_sale");
            case COMPLAINT -> List.of("recent_orders");
            case HUMAN_SERVICE, UNKNOWN -> List.of();
        };
        return new ToolBinding(schemas, delegate, exposed);
    }

    /**
     * 第二阶段精确绑定入口。selectedSkillKey 已由确定性节点选择且被 ContextAssembler 重新授权；
     * 本方法仍以显式 switch 解析，绝不按模型字符串反射 Bean。带依赖的物流/售后预览会同时暴露
     * recentOrders，使模型先取得属于当前用户的订单号，再调用目标只读工具。
     */
    public ToolBinding create(Intent intent, String selectedSkillKey, String tenantId, String userId,
                              String traceId, ConcurrentHashMap<String, Object> requestCache) {
        return create(intent, selectedSkillKey, tenantId, userId, traceId,
                OrderQueryScope.currentUser(), requestCache);
    }

    public ToolBinding create(Intent intent, String selectedSkillKey, String tenantId, String userId,
                              String traceId, OrderQueryScope orderScope,
                              ConcurrentHashMap<String, Object> requestCache) {
        if (selectedSkillKey == null || selectedSkillKey.isBlank()
                || intent == Intent.UNKNOWN || intent == Intent.HUMAN_SERVICE)
            return new ToolBinding(null, null, List.of());
        ScopedCommerceTools delegate = new ScopedCommerceTools(intent, tenantId, userId, store, gateway,
                telemetry, traceId, orderScope, requestCache);
        return switch (selectedSkillKey) {
            case "query_product" -> require(intent == Intent.PRE_SALE, selectedSkillKey,
                    new ToolBinding(new PreSaleTools(delegate), delegate, List.of("query_product")));
            case "recent_orders" -> require(intent == Intent.IN_SALE || intent == Intent.AFTER_SALE
                            || intent == Intent.COMPLAINT, selectedSkillKey,
                    new ToolBinding(new RecentOrderTools(delegate), delegate, List.of("recent_orders")));
            case "query_logistics" -> require(intent == Intent.IN_SALE, selectedSkillKey,
                    new ToolBinding(new InSaleTools(delegate), delegate,
                            List.of("recent_orders", "query_logistics")));
            case "preview_after_sale" -> require(intent == Intent.AFTER_SALE, selectedSkillKey,
                    new ToolBinding(new AfterSaleTools(delegate), delegate,
                            List.of("recent_orders", "preview_after_sale")));
            default -> throw new SecurityException("未授权或未注册的 Skill：" + selectedSkillKey);
        };
    }

    private ToolBinding require(boolean allowed, String skillKey, ToolBinding binding) {
        if (!allowed) throw new SecurityException("当前 Agent 无权绑定 Skill：" + skillKey);
        return binding;
    }

    public record ToolBinding(Object schemas, ScopedCommerceTools auditSource, List<String> skillKeys) {
        public ToolBinding { skillKeys = List.copyOf(skillKeys); }
        public List<String> auditTrail() {
            return auditSource == null ? List.of() : auditSource.auditTrail();
        }
    }

    public static final class PreSaleTools {
        private final ScopedCommerceTools delegate;
        PreSaleTools(ScopedCommerceTools delegate) { this.delegate = delegate; }

        @Tool(name = "queryProduct", description = "按名称、分类或描述查询当前租户商品、价格、库存和参数")
        public ProjectedList<ProductView> queryProduct(@ToolParam(description = "商品名称、品类或关键词") String query) {
            return ToolResultProjector.products(delegate.queryProduct(query));
        }
    }

    public static final class InSaleTools {
        private final ScopedCommerceTools delegate;
        InSaleTools(ScopedCommerceTools delegate) { this.delegate = delegate; }

        @Tool(name = "recentOrders", description = "查询当前会话授权范围内的订单；商品会话默认仅当前商品，不接受身份、店铺或商品参数")
        public ProjectedList<OrderView> recentOrders() {
            return ToolResultProjector.orders(delegate.recentOrders());
        }

        @Tool(name = "queryLogistics", description = "查询 recentOrders 已返回订单的物流轨迹；拒绝授权范围外订单号")
        public ProjectedList<LogisticsView> queryLogistics(
                @ToolParam(description = "必须来自 recentOrders 结果的完整订单号") String orderId) {
            return ToolResultProjector.logistics(delegate.queryLogistics(orderId));
        }
    }

    /** 只披露近期订单一个 Schema，供不需要物流或售后资格的节点使用。 */
    public static final class RecentOrderTools {
        private final ScopedCommerceTools delegate;
        RecentOrderTools(ScopedCommerceTools delegate) { this.delegate = delegate; }

        @Tool(name = "recentOrders", description = "查询当前会话授权范围内的订单；商品会话默认仅当前商品，不接受身份、店铺或商品参数")
        public ProjectedList<OrderView> recentOrders() {
            return ToolResultProjector.orders(delegate.recentOrders());
        }
    }

    public static final class AfterSaleTools {
        private final ScopedCommerceTools delegate;
        AfterSaleTools(ScopedCommerceTools delegate) { this.delegate = delegate; }

        @Tool(name = "recentOrders", description = "查询当前会话授权范围内的订单；商品会话默认仅当前商品，不接受身份、店铺或商品参数")
        public ProjectedList<OrderView> recentOrders() {
            return ToolResultProjector.orders(delegate.recentOrders());
        }

        @Tool(name = "previewAfterSale", description = "只读预览订单售后资格，不提交退款或补偿")
        public String previewAfterSale(
                @ToolParam(description = "必须来自 recentOrders 结果的完整订单号") String orderId) {
            return delegate.previewAfterSale(orderId);
        }
    }

    public static final class ComplaintTools {
        private final ScopedCommerceTools delegate;
        ComplaintTools(ScopedCommerceTools delegate) { this.delegate = delegate; }

        @Tool(name = "recentOrders", description = "投诉沟通前查询当前会话授权范围内的订单，不接受身份、店铺或商品参数")
        public ProjectedList<OrderView> recentOrders() {
            return ToolResultProjector.orders(delegate.recentOrders());
        }
    }
}
