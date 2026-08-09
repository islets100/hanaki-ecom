package com.hanaki.ecom.agent;

import java.util.Map;

/**
 * 订单查询的服务端可信范围。模型既不能传入 tenantId/userId，也不能把商品会话扩大成全店或全平台查询。
 */
public record OrderQueryScope(Mode mode, String storeId, String productId, String productName) {
    public enum Mode { CURRENT_USER, CURRENT_STORE, CURRENT_PRODUCT }

    public OrderQueryScope {
        mode = mode == null ? Mode.CURRENT_USER : mode;
        storeId = normalize(storeId);
        productId = normalize(productId);
        productName = normalize(productName);
        if (mode == Mode.CURRENT_STORE && storeId.isBlank())
            throw new IllegalArgumentException("当前店铺订单范围缺少 storeId");
        if (mode == Mode.CURRENT_PRODUCT && (storeId.isBlank() || productId.isBlank()))
            throw new IllegalArgumentException("当前商品订单范围缺少 storeId 或 productId");
    }

    public static OrderQueryScope currentUser() {
        return new OrderQueryScope(Mode.CURRENT_USER, "", "", "");
    }

    public static OrderQueryScope currentStore(String storeId) {
        return new OrderQueryScope(Mode.CURRENT_STORE, storeId, "", "");
    }

    public static OrderQueryScope currentProduct(String storeId, String productId, String productName) {
        return new OrderQueryScope(Mode.CURRENT_PRODUCT, storeId, productId, productName);
    }

    /** 店铺商品会话默认锁定当前商品；只有用户明确询问整店购买记录时才扩大到当前店铺。 */
    public static OrderQueryScope resolve(String channelKind, String storeId, String productId,
                                          String productName, String message) {
        if (!"STORE".equalsIgnoreCase(normalize(channelKind))) return currentUser();
        if (asksForWholeStore(message)) return currentStore(storeId);
        return currentProduct(storeId, productId, productName);
    }

    public static OrderQueryScope fromBusinessFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) return currentUser();
        Mode parsed;
        try { parsed = Mode.valueOf(String.valueOf(facts.getOrDefault("orderQueryScope", "CURRENT_USER"))); }
        catch (IllegalArgumentException ignored) { parsed = Mode.CURRENT_USER; }
        return new OrderQueryScope(parsed,
                String.valueOf(facts.getOrDefault("scopeStoreId", "")),
                String.valueOf(facts.getOrDefault("scopeProductId", "")),
                String.valueOf(facts.getOrDefault("scopeProductName", "")));
    }

    public Map<String, Object> cacheArguments() {
        return Map.of("scope", mode.name(), "storeId", storeId, "productId", productId);
    }

    public String emptyMessage() {
        return switch (mode) {
            case CURRENT_PRODUCT -> "未查询到你购买当前商品" +
                    (productName.isBlank() ? "" : "「" + productName + "」") +
                    "的订单，因此暂时没有可查询的发货或物流信息。";
            case CURRENT_STORE -> "未查询到你在当前店铺购买商品的订单，因此暂时没有可查询的发货或物流信息。";
            case CURRENT_USER -> "当前账号还没有订单，因此暂时没有可查询的物流记录。完成余额支付后，系统会立即创建订单、计划发货时间和预计到达时间。";
        };
    }

    private static boolean asksForWholeStore(String message) {
        String text = normalize(message).replaceAll("\\s+", "");
        boolean namesStore = contains(text, "这家店", "本店", "当前店铺", "该店", "店铺");
        boolean namesOrders = contains(text, "订单", "买过", "购买记录", "买的商品", "购买的商品", "全部商品", "所有商品");
        return namesStore && namesOrders;
    }

    private static boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String normalize(String value) { return value == null ? "" : value.strip(); }
}
