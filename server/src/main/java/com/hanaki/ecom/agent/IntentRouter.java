package com.hanaki.ecom.agent;

import com.hanaki.ecom.context.ContextAssemblyRequest;
import com.hanaki.ecom.context.ContextNode;
import com.hanaki.ecom.context.SkillDisclosurePhase;
import com.hanaki.ecom.context.TrustedRequestContext;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.ModelRoute;
import com.hanaki.ecom.domain.Domain.RouteResult;
import com.hanaki.ecom.domain.Domain.RoutingConfidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主 Agent 的受控路由器。
 *
 * <p>模型只提供主候选、次候选和“模型自评置信度”。应用侧会结合候选分差、最近上下文一致性
 * 与确定性规则重新校准；最终只返回固定 Intent 枚举。用户明确说“转人工”“投诉”“退货”等时，
 * 确定性规则优先，既避免模型抖动，也保证“缺少 orderId”只被视为业务参数不完整，而不会把已经
 * 明确的 AFTER_SALE 意图降成 UNKNOWN。</p>
 */
@Service
public class IntentRouter {
    private static final Pattern ORDER_ID = Pattern.compile("(?:OD)?\\d{8,16}", Pattern.CASE_INSENSITIVE);
    private final AiModelGateway model;
    private final double highConfidence;
    private final double mediumConfidence;

    public IntentRouter(AiModelGateway model,
                        @Value("${agent.routing.high-confidence:0.82}") double highConfidence,
                        @Value("${agent.routing.medium-confidence:0.55}") double mediumConfidence) {
        this.model = model;
        if (mediumConfidence <= 0 || highConfidence <= mediumConfidence || highConfidence > 1)
            throw new IllegalArgumentException("路由阈值必须满足 0 < medium < high <= 1");
        this.highConfidence = highConfidence;
        this.mediumConfidence = mediumConfidence;
    }

    /**
     * 兼容测试和非 Graph 调用的路由入口。执行顺序固定为：风控强制人工 → 明确关键词规则 →
     * 模型结构化分类 → 应用侧置信度校准。前两步命中后不会调用模型。
     */
    public RouteResult route(String content, List<String> recentMessages, boolean forceHandoff) {
        if (forceHandoff) {
            return deterministic(Intent.HUMAN_SERVICE, content, "输入风控要求进入通用人工接管流程");
        }
        Intent rule = deterministicIntent(content);
        if (rule != Intent.UNKNOWN) return deterministic(rule, content, "命中服务端确定性意图规则");
        ModelRoute prediction = model.route(content == null ? "" : content,
                recentMessages == null ? List.of() : recentMessages);
        return validatePrediction(content, recentMessages, prediction);
    }

    /**
     * 正式 Graph 路由入口。主 Agent 只披露当前问题、少量最近消息和路由摘要；ContextPolicy 明确
     * 禁止加载业务 Agent 细节、订单事实、RAG 文档及任何 Skill Schema。
     */
    public RouteResult route(TrustedRequestContext trusted, String content,
                             List<String> recentMessages, boolean forceHandoff) {
        if (forceHandoff) {
            return deterministic(Intent.HUMAN_SERVICE, content, "输入风控要求进入通用人工接管流程");
        }
        List<String> recent = recentMessages == null ? List.of() : recentMessages;
        Intent rule = deterministicIntent(content);
        if (rule != Intent.UNKNOWN) return deterministic(rule, content, "命中服务端确定性意图规则");
        ContextAssemblyRequest request = new ContextAssemblyRequest(trusted, Intent.UNKNOWN,
                ContextNode.INTENT_ROUTE, 0, content, "", recent, List.of(), Map.of(), List.of(),
                "", SkillDisclosurePhase.NONE, "qwen-plus", 0);
        return validatePrediction(content, recent, model.route(request));
    }

    /**
     * 校验并校准模型候选。模型自评、第一/第二候选分差和最近上下文一致性共同形成最终 confidence；
     * Graph 实际消费的是由该分值映射出的 confidenceBand 与 needClarification，而不是模型原始分数。
     */
    private RouteResult validatePrediction(String content, List<String> recentMessages, ModelRoute prediction) {
        if (prediction == null) throw new ModelCallException("路由模型没有返回结构化结果");
        Intent primary = parseIntent(prediction.intent());
        Intent secondary = parseIntent(prediction.secondaryIntent());
        if (secondary == primary) secondary = Intent.UNKNOWN;

        double modelSelfConfidence = clamp(prediction.confidence());
        double secondaryConfidence = clamp(prediction.secondaryConfidence());
        double candidateMargin = secondary == Intent.UNKNOWN
                ? 1d : Math.max(0d, modelSelfConfidence - secondaryConfidence);
        double marginSignal = secondary == Intent.UNKNOWN ? 1d : Math.min(1d, candidateMargin / 0.35d);

        Intent contextIntent = recentContextIntent(recentMessages);
        boolean contextConsistent = contextIntent == primary;
        boolean contextConflict = contextIntent != Intent.UNKNOWN && contextIntent != primary;
        double contextSignal = contextConsistent ? 1d : contextConflict ? 0d : 0.5d;

        /*
         * 这里使用可解释的固定权重，而不是再次让模型“评判自己的置信度”。自评分占 75%，候选分差
         * 占 15%，上下文一致性占 10%。生产中可以把权重替换为离线校准模型，但输出仍必须落在同一
         * 强类型协议中，并保留版本号用于回放。
         */
        double calibrated = clamp(0.75d * modelSelfConfidence
                + 0.15d * marginSignal + 0.10d * contextSignal);
        RoutingConfidence band = band(calibrated);
        boolean clarify = primary == Intent.UNKNOWN || band == RoutingConfidence.LOW
                || (band == RoutingConfidence.MEDIUM && !contextConsistent);

        List<Intent> candidates = secondary == Intent.UNKNOWN
                ? List.of(primary) : List.of(primary, secondary);
        String modelReason = prediction.reason() == null || prediction.reason().isBlank()
                ? "模型未提供路由理由" : prediction.reason().strip();
        String reason = modelReason + "；模型自评=" + rounded(modelSelfConfidence)
                + "，候选分差=" + rounded(candidateMargin)
                + "，上下文=" + (contextConsistent ? "一致" : contextConflict ? "冲突" : "无明确信号")
                + "，校准后=" + rounded(calibrated) + "(" + band + ")";
        return new RouteResult(primary, secondary, modelSelfConfidence, calibrated, band,
                candidates, entities(content), clarify, reason);
    }

    /** 明确规则采用固定优先级：人工 > 投诉 > 售后 > 售中 > 售前。 */
    private Intent deterministicIntent(String content) {
        String text = content == null ? "" : content.strip();
        if (contains(text, "转人工", "人工客服", "真人客服", "人工处理", "人工接管"))
            return Intent.HUMAN_SERVICE;
        if (contains(text, "我要投诉", "平台介入", "12315", "举报商家", "曝光商家"))
            return Intent.COMPLAINT;
        if (contains(text, "退货", "退款", "换货", "补偿", "售后", "退款进度"))
            return Intent.AFTER_SALE;
        if (contains(text, "物流", "快递", "发货", "支付", "取消订单", "修改地址", "订单到哪"))
            return Intent.IN_SALE;
        if (contains(text, "商品参数", "对比", "库存", "优惠", "活动", "推荐", "适合买吗"))
            return Intent.PRE_SALE;
        return Intent.UNKNOWN;
    }

    private RouteResult deterministic(Intent intent, String content, String reason) {
        return new RouteResult(intent, Intent.UNKNOWN, 1d, 1d, RoutingConfidence.HIGH,
                List.of(intent), entities(content), false, reason + "：" + intent.name());
    }

    /** 只查看少量最近消息的明确领域信号，不从历史文本提取 tenantId/userId 或业务主键。 */
    private Intent recentContextIntent(List<String> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return Intent.UNKNOWN;
        int start = Math.max(0, recentMessages.size() - 4);
        for (int index = recentMessages.size() - 1; index >= start; index--) {
            Intent value = deterministicIntent(recentMessages.get(index));
            if (value != Intent.UNKNOWN && value != Intent.HUMAN_SERVICE) return value;
        }
        return Intent.UNKNOWN;
    }

    private RoutingConfidence band(double value) {
        if (value >= highConfidence) return RoutingConfidence.HIGH;
        if (value >= mediumConfidence) return RoutingConfidence.MEDIUM;
        return RoutingConfidence.LOW;
    }

    private Intent parseIntent(String value) {
        try { return Intent.valueOf(value == null ? "UNKNOWN" : value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) {
            throw new ModelCallException("路由模型返回了未注册的 intent：" + value, error);
        }
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }

    private double rounded(double value) { return Math.round(value * 1000d) / 1000d; }

    private boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    /** 订单号等实体仍由确定性代码提取，不能接受模型虚构的业务主键。 */
    private Map<String, String> entities(String text) {
        String safe = text == null ? "" : text;
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = ORDER_ID.matcher(safe);
        if (matcher.find()) values.put("orderId", matcher.group().toUpperCase(Locale.ROOT));
        if (safe.contains("耳机")) values.put("productId", "P1002");
        return values;
    }
}
