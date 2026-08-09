package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentResult;
import com.hanaki.ecom.domain.Domain.ExecutionStatus;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.LogisticsEvent;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.observability.AgentTelemetryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 物流问题使用确定性的“订单 -> 物流”工具链。
 *
 * 模型适合做意图理解和语言表达，但订单归属、当前状态与时间属于实时业务事实，不能让模型
 * 用“正在查询”之类的占位文本代替工具调用。因此本服务先查询当前登录用户的订单，再把
 * 可信订单号交给物流工具，并直接根据数据库结果形成最终回答。
 */
@Service
public class LogisticsAgentService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final EcommerceStore store;
    private final ToolGateway gateway;
    private final AgentTelemetryService telemetry;

    public LogisticsAgentService(EcommerceStore store, ToolGateway gateway,
                                 AgentTelemetryService telemetry) {
        this.store = store;
        this.gateway = gateway;
        this.telemetry = telemetry;
    }

    /** 支持“查到了吗”这类承接上一轮物流问题的短句。 */
    public boolean matches(String message, List<String> recentMessages) {
        String current = message == null ? "" : message;
        if (contains(current, "物流", "快递", "配送", "运输", "发货", "到达", "到哪", "揽收")) return true;

        // STORE_CONTEXT / OFFICIAL_CONTEXT 是服务端注入的资料快照，其中本来就会出现“物流”二字，
        // 不能据此把“查看客服聊天记录”等问题误判成物流查询。只有承接式短问才回看真实对话消息。
        boolean continuation = contains(current, "查到了", "查一下", "怎么样", "现在呢", "有结果", "更新了吗");
        if (!continuation) return false;
        String dialogue = recentMessages == null ? "" : recentMessages.stream()
                .filter(item -> item != null && !item.startsWith("STORE_CONTEXT:") && !item.startsWith("OFFICIAL_CONTEXT:"))
                .reduce((left, right) -> left + " " + right).orElse("");
        return contains(dialogue, "物流", "快递", "配送", "运输", "发货", "到达", "到哪", "揽收");
    }

    public AgentResult answer(String tenantId, String userId, String message) {
        return answer(tenantId, userId, message, OrderQueryScope.currentUser());
    }

    public AgentResult answer(String tenantId, String userId, String message, OrderQueryScope orderScope) {
        ScopedCommerceTools scoped = new ScopedCommerceTools(Intent.IN_SALE, tenantId, userId, store, gateway,
                telemetry, telemetry.currentTraceId(), orderScope);

        // 第一步必须查询当前登录用户的订单，订单号绝不直接相信用户输入。
        List<OrderSummary> orders = scoped.recentOrders();
        if (orders.isEmpty()) {
            return result(orderScope.emptyMessage(), scoped.auditTrail());
        }

        // 用户明确给出本人订单号时优先使用；否则查询最新订单。
        String normalized = message == null ? "" : message.toUpperCase(Locale.ROOT);
        OrderSummary explicitlySelected = orders.stream()
                .filter(order -> normalized.contains(order.id().toUpperCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (explicitlySelected == null && orders.size() > 1) {
            String choices = orders.stream().limit(5)
                    .map(order -> order.id() + "（" + order.productName() + "）")
                    .reduce((left, right) -> left + "、" + right).orElse("");
            return new AgentResult("查询到多笔符合当前范围的订单：" + choices + "。请提供要查询的订单号。",
                    List.of(), scoped.auditTrail(), ExecutionStatus.NEED_CLARIFICATION,
                    null, null, false, "ORDER_SELECTION_REQUIRED");
        }
        OrderSummary selected = explicitlySelected == null ? orders.getFirst() : explicitlySelected;

        // 第二步只把第一步返回的可信订单号交给物流工具。
        List<LogisticsEvent> events = scoped.queryLogistics(selected.id());
        return result(format(selected, events), scoped.auditTrail());
    }

    private AgentResult result(String answer, List<String> audit) {
        return new AgentResult(answer, List.of(), audit, ExecutionStatus.COMPLETED,
                null, null, false, "ORDER_LOGISTICS_TOOL_CHAIN");
    }

    private String format(OrderSummary order, List<LogisticsEvent> events) {
        StringBuilder answer = new StringBuilder()
                .append("已查询到订单 ").append(order.id()).append("（").append(order.productName()).append("）。\n")
                .append("当前物流状态：").append(value(order.logisticsStatus(), "暂无状态")).append("。\n")
                .append("计划发货时间：").append(time(order.plannedShipAt())).append("。\n")
                .append("预计到达时间：").append(time(order.estimatedArrivalAt())).append("。");

        if (events.isEmpty()) {
            answer.append("\n数据库中暂时没有新的物流轨迹；以上计划时间来自该订单的履约记录。");
        } else {
            answer.append("\n最新物流轨迹：");
            events.stream().limit(5).forEach(event -> answer.append("\n- ")
                    .append(formatEventTime(event.time())).append(" ")
                    .append(value(event.location(), "未知地点")).append("：")
                    .append(value(event.description(), "状态已更新")));
        }
        return answer.toString();
    }

    private String time(Instant value) { return value == null ? "尚未生成" : TIME.format(value); }
    private String formatEventTime(String value) {
        if (value == null || value.isBlank()) return "时间未知";
        try { return TIME.format(Instant.parse(value)); }
        catch (RuntimeException ignored) { return value; }
    }
    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
    private boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
