package com.hanaki.ecom.context;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.RiskLevel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 能力卡、完整 Schema 元数据和节点授权的统一注册中心。
 *
 * <p>第一阶段只使用 {@link SkillCard} 做低 Token 的能力选择；第二阶段必须拿着已选 skillKey、
 * ContextPolicy 和实时风险重新授权，才返回 {@link SkillSchema}。执行器不通过模型字符串反射查找，
 * 只允许 ScopedToolBindingFactory 对本注册中心返回的键做显式 switch，从源头阻断伪造 skillKey。</p>
 */
@Service
public class ProgressiveSkillRegistry {
    private final Map<String, SkillDefinition> definitions;

    public ProgressiveSkillRegistry() {
        Map<String, SkillDefinition> values = new LinkedHashMap<>();
        register(values, Intent.PRE_SALE, card("query_product", "查询商品",
                "查询当前租户商品、价格、库存和参数", RiskLevel.LOW, List.of("query"), false, false),
                schema("query_product", "queryProduct", "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":120}}}"), Set.of());
        register(values, Intent.IN_SALE, card("recent_orders", "查询近期订单",
                "查询当前会话授权范围内的订单摘要；商品会话默认仅当前商品", RiskLevel.LOW, List.of(), false, false),
                schema("recent_orders", "recentOrders", "{\"type\":\"object\",\"additionalProperties\":false}"), Set.of());
        register(values, Intent.IN_SALE, card("query_logistics", "查询物流",
                "查询已确认且属于当前用户订单的物流轨迹", RiskLevel.LOW, List.of("orderId"), false, false),
                schema("query_logistics", "queryLogistics", "{\"type\":\"object\",\"required\":[\"orderId\"],\"properties\":{\"orderId\":{\"type\":\"string\",\"maxLength\":40}}}"), Set.of("recent_orders"));
        register(values, Intent.AFTER_SALE, card("recent_orders", "查询近期订单",
                "查询当前会话授权范围内的订单摘要；商品会话默认仅当前商品", RiskLevel.LOW, List.of(), false, false),
                schema("recent_orders", "recentOrders", "{\"type\":\"object\",\"additionalProperties\":false}"), Set.of());
        register(values, Intent.AFTER_SALE, card("preview_after_sale", "预览售后资格",
                "只读预览已确认订单的售后资格，不创建退款或补偿", RiskLevel.MEDIUM,
                        List.of("orderId"), false, false),
                schema("preview_after_sale", "previewAfterSale", "{\"type\":\"object\",\"required\":[\"orderId\"],\"properties\":{\"orderId\":{\"type\":\"string\",\"maxLength\":40}}}"), Set.of("recent_orders"));
        register(values, Intent.COMPLAINT, card("recent_orders", "查询近期订单",
                "投诉沟通前查询当前会话授权范围内的订单摘要", RiskLevel.LOW, List.of(), false, false),
                schema("recent_orders", "recentOrders", "{\"type\":\"object\",\"additionalProperties\":false}"), Set.of());
        // 高风险写 Skill 只登记能力与安全属性，不在 ANSWER_GENERATION 策略中授权；真实提交仍由
        // ToolGateway 在用户确认、双重审批和幂等校验后确定性执行，模型永远拿不到该 Schema。
        register(values, Intent.AFTER_SALE, card("submit_refund", "提交退款",
                "为已确认订单提交退款写操作", RiskLevel.HIGH,
                        List.of("orderId", "businessTaskId", "confirmationId"), true, true),
                schema("submit_refund", "submitRefund", "{\"type\":\"object\",\"required\":[\"orderId\",\"businessTaskId\",\"confirmationId\"]}"), Set.of());
        definitions = Map.copyOf(values);
    }

    /**
     * 当前业务子图使用确定性节点完成第一阶段选择，避免为了选一个只读能力额外调用模型。
     * 选择只基于已确定 intent 和用户本轮文本，返回值仍需在第二阶段重新授权。
     */
    public String selectCard(Intent intent, String message) {
        String text = message == null ? "" : message;
        return switch (intent) {
            case PRE_SALE -> "query_product";
            case IN_SALE -> contains(text, "物流", "快递", "到哪", "配送", "运单")
                    ? "query_logistics" : "recent_orders";
            case AFTER_SALE -> contains(text, "退货", "退款", "换货", "售后", "质量", "坏", "故障")
                    ? "preview_after_sale" : "recent_orders";
            case COMPLAINT -> contains(text, "订单", "物流", "退款", "商品", "商家") ? "recent_orders" : "";
            case HUMAN_SERVICE, UNKNOWN -> "";
        };
    }

    public List<SkillCard> cards(Intent intent, ContextPolicy policy) {
        return definitions.values().stream()
                .filter(definition -> definition.intent() == intent)
                .map(SkillDefinition::card)
                .filter(card -> policy.allowedSkillKeys().contains(card.skillKey()))
                .toList();
    }

    /** 第二阶段授权：返回主 Skill 及其只读前置依赖，任一依赖未获策略授权就整体失败。 */
    public AuthorizedSkillSet authorize(Intent intent, ContextPolicy policy, String selectedSkillKey,
                                        SkillDisclosurePhase phase, RiskLevel riskLevel) {
        if (phase != SkillDisclosurePhase.SCHEMA || selectedSkillKey == null || selectedSkillKey.isBlank())
            return AuthorizedSkillSet.empty();
        if (!policy.allowFullSkillSchema()) throw new SecurityException("当前节点禁止披露完整 Skill Schema");
        if (riskLevel == RiskLevel.BLOCKED) throw new SecurityException("输入已被风控阻断，禁止绑定任何 Skill");
        SkillDefinition primary = definition(intent, selectedSkillKey);
        if (!policy.allowedSkillKeys().contains(selectedSkillKey))
            throw new SecurityException("当前 ContextPolicy 未授权 Skill：" + selectedSkillKey);
        LinkedHashSet<String> ordered = new LinkedHashSet<>(primary.dependencies());
        ordered.add(selectedSkillKey);
        List<SkillSchema> schemas = new ArrayList<>();
        for (String key : ordered) {
            if (!policy.allowedSkillKeys().contains(key))
                throw new SecurityException("Skill 前置依赖未获节点授权：" + key);
            SkillDefinition definition = definition(intent, key);
            if (definition.card().riskLevel() == RiskLevel.HIGH)
                throw new SecurityException("高风险写 Skill 不能在回答生成节点绑定：" + key);
            schemas.add(definition.schema());
        }
        return new AuthorizedSkillSet(primary.card(), List.copyOf(schemas));
    }

    private SkillDefinition definition(Intent intent, String key) {
        SkillDefinition value = definitions.get(intent.name() + ":" + key);
        if (value == null) throw new SecurityException("Agent 无权使用未注册 Skill：" + key);
        return value;
    }

    private void register(Map<String, SkillDefinition> target, Intent intent, SkillCard card,
                          SkillSchema schema, Set<String> dependencies) {
        target.put(intent.name() + ":" + card.skillKey(), new SkillDefinition(intent, card, schema, dependencies));
    }

    private SkillCard card(String key, String name, String purpose, RiskLevel risk,
                           List<String> parameters, boolean confirmation, boolean approval) {
        return new SkillCard(key, name, purpose, Set.of("ANSWER_GENERATION"), risk,
                List.copyOf(parameters), confirmation, approval);
    }

    private SkillSchema schema(String key, String toolName, String inputSchema) {
        return new SkillSchema(key, "skill-schema-v3", toolName, inputSchema,
                "{\"type\":\"object\"}", "scopedCommerceTools");
    }

    private boolean contains(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    public record SkillCard(String skillKey, String displayName, String purpose,
                            Set<String> applicableStates, RiskLevel riskLevel,
                            List<String> requiredParameterNames,
                            boolean requiresConfirmation, boolean requiresApproval) {}
    public record SkillSchema(String skillKey, String version, String toolName,
                              String inputJsonSchema, String outputJsonSchema,
                              String executorBinding) {}
    private record SkillDefinition(Intent intent, SkillCard card, SkillSchema schema,
                                   Set<String> dependencies) {}
    public record AuthorizedSkillSet(SkillCard primaryCard, List<SkillSchema> schemas) {
        public static AuthorizedSkillSet empty() { return new AuthorizedSkillSet(null, List.of()); }
        public List<String> skillKeys() { return schemas.stream().map(SkillSchema::skillKey).toList(); }
    }
}
