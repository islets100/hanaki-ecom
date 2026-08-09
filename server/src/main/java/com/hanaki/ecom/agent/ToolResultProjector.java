package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.LogisticsEvent;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.domain.Domain.Product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 将只读工具的数据库实体投影为可进入模型上下文的稳定白名单结构。
 *
 * <p>投影不使用反射，也不把整行实体直接序列化：新增数据库字段不会自动暴露给模型。订单投影
 * 保留金额精度、业务状态、计划/预计时间和完整订单号（后续物流工具需要），但删除 tenantId、
 * userId 等模型无需看到的隔离字段。数组显式携带 totalCount/returnedCount/truncated，模型不会把
 * “只返回前 8 条”误解成“总共只有 8 条”。</p>
 */
public final class ToolResultProjector {
    private static final int PRODUCT_LIMIT = 8;
    private static final int ORDER_LIMIT = 10;
    private static final int LOGISTICS_LIMIT = 20;
    private ToolResultProjector() {}

    public static ProjectedList<ProductView> products(List<Product> source) {
        List<Product> safe = source == null ? List.of() : source;
        List<ProductView> items = safe.stream().limit(PRODUCT_LIMIT).map(product -> new ProductView(
                product.id(), product.name(), product.subtitle(), product.category(), product.price(),
                product.oldPrice(), product.stock(), product.badge(), limit(product.attributesJson(), 600))).toList();
        return list(safe.size(), items, "commerce-product-read-v1");
    }

    public static ProjectedList<OrderView> orders(List<OrderSummary> source) {
        List<OrderSummary> safe = source == null ? List.of() : source;
        List<OrderView> items = safe.stream().limit(ORDER_LIMIT).map(order -> new OrderView(
                order.id(), order.maskedId(), order.productName(), order.sku(), order.amount(), order.status(),
                order.paymentStatus(), order.logisticsStatus(), order.storeName(), order.plannedShipAt(),
                order.estimatedArrivalAt(), order.createdAt())).toList();
        return list(safe.size(), items, "commerce-order-read-v1");
    }

    public static ProjectedList<LogisticsView> logistics(List<LogisticsEvent> source) {
        List<LogisticsEvent> safe = source == null ? List.of() : source;
        List<LogisticsView> items = safe.stream().limit(LOGISTICS_LIMIT)
                .map(event -> new LogisticsView(event.time(), event.location(), event.description())).toList();
        return list(safe.size(), items, "commerce-logistics-read-v1");
    }

    /** 审计记录只保存确定性摘要，不复制订单内容、位置或用户问题。 */
    public static String auditSummary(Object value) {
        if (value instanceof List<?> list)
            return "totalCount=" + list.size() + ";returnedCount=" + list.size() + ";truncated=false";
        String text = String.valueOf(value).replaceAll("[\\r\\n]+", " ");
        return "textLength=" + text.length() + ";textDigest=" + CacheKeyBuilder.digest(text).substring(0, 16);
    }

    private static <T> ProjectedList<T> list(int total, List<T> items, String sourceVersion) {
        return new ProjectedList<>(total, items.size(), items.size() < total, List.copyOf(items),
                Instant.now(), sourceVersion, List.of());
    }

    private static String limit(String value, int maxChars) {
        if (value == null) return "";
        return value.substring(0, Math.min(maxChars, value.length()));
    }

    public record ProjectedList<T>(int totalCount, int returnedCount, boolean truncated, List<T> items,
                                   Instant queriedAt, String sourceVersion, List<String> riskFlags) {}
    public record ProductView(String productId, String name, String subtitle, String category,
                              BigDecimal price, BigDecimal oldPrice, int stock, String badge, String attributes) {}
    public record OrderView(String orderId, String maskedOrderId, String productName, String sku,
                            BigDecimal amount, String status, String paymentStatus, String logisticsStatus,
                            String storeName, Instant plannedShipAt, Instant estimatedArrivalAt, Instant createdAt) {}
    public record LogisticsView(String time, String location, String description) {}
}
