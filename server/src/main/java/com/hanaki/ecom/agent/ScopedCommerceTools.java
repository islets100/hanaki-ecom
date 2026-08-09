package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.LogisticsEvent;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.domain.Domain.Product;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.observability.AgentTelemetryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每次候选生成都会创建一个实例，并把可信 tenantId/userId 固定在构造函数中。
 * 因此模型的工具参数里根本不存在 tenantId/userId，无法通过提示词伪造其他用户身份。
 * 这里只暴露只读工具；退款、建单等写操作仍由模型外的 ToolGateway 执行。
 */
public final class ScopedCommerceTools {
    private final Intent agent;
    private final String tenantId;
    private final String userId;
    private final EcommerceStore store;
    private final ToolGateway gateway;
    private final AgentTelemetryService telemetry;
    private final String traceId;
    private final ConcurrentHashMap<String, Object> requestCache;
    private final OrderQueryScope orderScope;
    private final Set<String> authorizedOrderIds = ConcurrentHashMap.newKeySet();
    private final List<String> audit = new CopyOnWriteArrayList<>();

    public ScopedCommerceTools(Intent agent, String tenantId, String userId,
                               EcommerceStore store, ToolGateway gateway) {
        this(agent, tenantId, userId, store, gateway, null, "",
                OrderQueryScope.currentUser(), new ConcurrentHashMap<>());
    }

    public ScopedCommerceTools(Intent agent, String tenantId, String userId,
                               EcommerceStore store, ToolGateway gateway,
                               AgentTelemetryService telemetry, String traceId) {
        this(agent, tenantId, userId, store, gateway, telemetry, traceId,
                OrderQueryScope.currentUser(), new ConcurrentHashMap<>());
    }

    public ScopedCommerceTools(Intent agent, String tenantId, String userId,
                               EcommerceStore store, ToolGateway gateway,
                               AgentTelemetryService telemetry, String traceId,
                               ConcurrentHashMap<String, Object> requestCache) {
        this(agent, tenantId, userId, store, gateway, telemetry, traceId,
                OrderQueryScope.currentUser(), requestCache);
    }

    public ScopedCommerceTools(Intent agent, String tenantId, String userId,
                               EcommerceStore store, ToolGateway gateway,
                               AgentTelemetryService telemetry, String traceId,
                               OrderQueryScope orderScope) {
        this(agent, tenantId, userId, store, gateway, telemetry, traceId,
                orderScope, new ConcurrentHashMap<>());
    }

    public ScopedCommerceTools(Intent agent, String tenantId, String userId,
                               EcommerceStore store, ToolGateway gateway,
                               AgentTelemetryService telemetry, String traceId,
                               OrderQueryScope orderScope,
                               ConcurrentHashMap<String, Object> requestCache) {
        this.agent = agent;
        this.tenantId = tenantId;
        this.userId = userId;
        this.store = store;
        this.gateway = gateway;
        this.telemetry = telemetry;
        this.traceId = traceId;
        this.orderScope = orderScope == null ? OrderQueryScope.currentUser() : orderScope;
        this.requestCache = requestCache;
    }

    @Tool(name = "queryProduct", description = "按名称、分类或描述查询当前租户商品、价格、库存和参数")
    public List<Product> queryProduct(
            @ToolParam(description = "用户提到的商品名称、品类或关键词") String query) {
        return observed("queryProduct", Map.of("query", query == null ? "" : query), () -> {
            gateway.assertAllowed(agent, "query_product");
            String keyword = query == null ? "" : query.strip().toLowerCase();
            List<Product> result = store.products(tenantId).stream()
                    .filter(p -> keyword.isBlank() || (p.name() + p.subtitle() + p.category())
                            .toLowerCase().contains(keyword))
                    .limit(8).toList();
            return result;
        });
    }

    @Tool(name = "recentOrders", description = "查询当前会话授权范围内的订单；商品会话默认仅当前商品，不接受身份、店铺或商品参数")
    public List<OrderSummary> recentOrders() {
        gateway.assertAllowed(agent, "query_order");
        List<OrderSummary> result = observed("recentOrders", orderScope.cacheArguments(), () -> {
            return switch (orderScope.mode()) {
                case CURRENT_PRODUCT -> store.recentOrdersForProduct(
                        tenantId, userId, orderScope.storeId(), orderScope.productId());
                case CURRENT_STORE -> store.recentOrdersForStore(tenantId, userId, orderScope.storeId());
                case CURRENT_USER -> store.recentOrders(tenantId, userId);
            };
        });
        result.forEach(order -> authorizedOrderIds.add(order.id().toUpperCase(Locale.ROOT)));
        return result;
    }

    @Tool(name = "queryLogistics", description = "只查询 recentOrders 已返回订单的最新物流轨迹")
    public List<LogisticsEvent> queryLogistics(
            @ToolParam(description = "必须来自 recentOrders 工具结果的完整订单号") String orderId) {
        gateway.assertAllowed(agent, "query_logistics");
        if (!isAuthorizedOrder(orderId)) return List.of();
        return observed("queryLogistics", Map.of("orderId", orderId == null ? "" : orderId), () -> {
            List<LogisticsEvent> result = store.logistics(tenantId, userId, orderId);
            return result;
        });
    }

    @Tool(name = "previewAfterSale", description = "只读预览当前用户订单是否仍在普通七天售后期；不会提交退款")
    public String previewAfterSale(
            @ToolParam(description = "必须来自 recentOrders 工具结果的完整订单号") String orderId) {
        gateway.assertAllowed(agent, "query_policy");
        if (!isAuthorizedOrder(orderId))
            return "未找到当前会话授权范围内的该订单，不能判断售后资格";
        return observed("previewAfterSale", Map.of("orderId", orderId == null ? "" : orderId), () -> {
            OrderSummary order = store.ownedOrder(tenantId, userId, orderId).orElse(null);
            if (order == null) {
                return "未找到当前账号可访问的该订单，不能判断售后资格";
            }
            long days = ChronoUnit.DAYS.between(order.createdAt(), Instant.now());
            boolean eligible = days <= 7;
            return eligible
                    ? "订单仍在七天普通售后期内；商品状态与附件仍需后续业务校验；当前仅为预览"
                    : "订单超过普通七天期限；需要核查质量问题例外或转人工申诉；当前仅为预览";
        });
    }

    public List<String> auditTrail() { return List.copyOf(audit); }

    private boolean isAuthorizedOrder(String orderId) {
        return orderId != null && authorizedOrderIds.contains(orderId.toUpperCase(Locale.ROOT));
    }

    private <T> T observed(String name, Object arguments, Supplier<T> work) {
        /*
         * 这是一次 Graph 运行内的工具请求合并，不是消息幂等缓存，也不跨用户/跨运行复用。Map 由
         * ScopedToolBindingFactory 为当前可信用户新建；键仍只存 SHA-256，避免订单号或查询词出现在
         * heap dump 的 ConcurrentHashMap 键中。失败 Supplier 不会写入 computeIfAbsent，因此超时、
         * 权限拒绝和数据库异常不会变成“可命中的工具结果”。
         */
        String cacheKey = requestCacheKey(agent, tenantId, userId, traceId, name, arguments);
        AtomicBoolean executed = new AtomicBoolean(false);
        Supplier<T> singleFlight = () -> {
            @SuppressWarnings("unchecked") T value = (T) requestCache.computeIfAbsent(cacheKey, ignored -> {
                executed.set(true);
                return work.get();
            });
            return value;
        };
        String tenantKey = telemetry == null ? "server-bound" : telemetry.scopedKey(tenantId);
        String userKey = telemetry == null ? "server-bound" : telemetry.scopedKey(userId);
        Map<String, Object> effective = Map.of(
                "agent", agent.name(),
                "tenantKey", tenantKey == null ? "server-bound" : tenantKey,
                "userKey", userKey == null ? "server-bound" : userKey,
                "permission", "READ_ONLY",
                "singleFlight", true);
        T result = telemetry == null ? singleFlight.get()
                : telemetry.observeTool(traceId, name, arguments, effective, singleFlight);
        // 测试替身或降级遥测实现可能不转发 Supplier；遥测不能改变真实工具调用语义。
        if (result == null) result = singleFlight.get();
        audit.add("toolResultRef=" + name + "#" + cacheKey.substring(0, 12)
                + ";scope=current-user;source=" + (executed.get() ? "LIVE" : "REQUEST_CACHE")
                + ";summary=" + ToolResultProjector.auditSummary(result));
        return result;
    }

    /**
     * 与 BestOfThreeGraphService 的快照预热共用同一 Key 构造，避免一边使用旧明文键、一边使用新摘要
     * 键而导致三个候选重复查库。方法只在包内开放，所有身份参数仍由服务端可信对象提供。
     */
    static String requestCacheKey(Intent agent, String tenantId, String userId, String traceId,
                                  String name, Object arguments) {
        CachePolicy policy = CachePolicy.runScopedTool();
        CacheContext context = new CacheContext(tenantId, userId, "",
                traceId == null || traceId.isBlank() ? "request-cache-instance" : traceId,
                agent.name(), "tool", "read-only-tools", "", "", "", "tools-v1", 0L, traceId);
        return CacheKeyBuilder.build(policy, context, name + "|" + String.valueOf(arguments), "commerce-read-v1");
    }
}
