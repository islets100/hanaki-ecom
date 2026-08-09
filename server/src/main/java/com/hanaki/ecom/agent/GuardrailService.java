package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.GuardResult;
import com.hanaki.ecom.domain.Domain.RiskLevel;
import com.hanaki.ecom.agent.SemanticRiskModelService.SemanticRiskDecision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class GuardrailService {
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final double SEMANTIC_ENFORCEMENT_CONFIDENCE = 0.65;
    private final SemanticRiskModelService semanticRisk;

    @Autowired
    public GuardrailService(SemanticRiskModelService semanticRisk) {
        this.semanticRisk = semanticRisk;
    }

    /** 供纯规则单元测试使用；生产 Spring 容器始终注入语义模型。 */
    public GuardrailService() { this.semanticRisk = null; }

    /**
     * 输入阶段的“语义风控节点”。
     *
     * <p>这里只识别提示词注入、恶意套取、敏感信息、辱骂威胁、投诉和人工诉求等语义信号。
     * tenantId/userId、订单归属、金额上限、工具白名单、参数 Schema、幂等与用户确认都属于确定性
     * 策略引擎或 ToolGateway 的职责，绝不能因为本方法返回 LOW 就跳过那些校验。</p>
     *
     * <p>投诉和人工接管被刻意拆开：投诉进入 COMPLAINT 领域子 Graph；只有用户明确要求真人，
     * 或语义风险严重到自动流程不应继续时，forceHandoff 才为 true。</p>
     */
    public GuardResult inspectInput(String content) {
        String text = content == null ? "" : content.strip();
        List<String> flags = new ArrayList<>();
        if (text.length() > 2_000) flags.add("INPUT_TOO_LONG");
        if (contains(text, "忽略之前", "系统提示词", "developer message", "越狱", "绕过规则")) flags.add("PROMPT_INJECTION");
        if (contains(text, "其他用户", "别人的订单", "修改tenant", "跨租户", "管理员权限")) flags.add("CROSS_USER_ACCESS");
        if (PHONE.matcher(text).find() || CARD.matcher(text).find()) flags.add("SENSITIVE_DATA");
        if (contains(text, "投诉", "平台介入", "曝光", "12315", "举报")) flags.add("COMPLAINT");
        if (contains(text, "转人工", "人工客服", "真人客服", "人工处理", "人工接管"))
            flags.add("HUMAN_HANDOFF_REQUEST");
        if (contains(text, "人身威胁", "报复客服", "伤害客服")) flags.add("SEVERE_THREAT");

        // 超长输入和确定命中的注入/越权先由硬规则阻断，避免把明显恶意或超预算文本送给模型。
        boolean hardBlocked = flags.contains("PROMPT_INJECTION")
                || flags.contains("CROSS_USER_ACCESS") || flags.contains("INPUT_TOO_LONG");
        if (!hardBlocked && semanticRisk != null) {
            try {
                mergeSemantic(flags, semanticRisk.classify(text));
            } catch (RuntimeException unavailable) {
                // 风控模型故障不应让全站客服不可用；规则边界继续生效，并留下可观测的降级标记。
                flags.add("SEMANTIC_GUARD_DEGRADED");
            }
        }

        boolean blocked = flags.contains("PROMPT_INJECTION") || flags.contains("CROSS_USER_ACCESS") || flags.contains("INPUT_TOO_LONG");
        boolean handoff = flags.contains("HUMAN_HANDOFF_REQUEST") || flags.contains("SEVERE_THREAT");
        RiskLevel level = blocked ? RiskLevel.BLOCKED
                : handoff || flags.contains("COMPLAINT") ? RiskLevel.HIGH :
                flags.contains("SENSITIVE_DATA") ? RiskLevel.MEDIUM : RiskLevel.LOW;
        String message = blocked ? "为保护账户与订单隐私，我不能执行该请求。你可以继续咨询自己账户下的商品和订单，或转人工核验。" : "";
        return new GuardResult(level, List.copyOf(flags), blocked, handoff, message);
    }

    private void mergeSemantic(List<String> flags, SemanticRiskDecision decision) {
        if (decision == null) return;
        double confidence = Math.max(0, Math.min(1, decision.confidence()));
        if (confidence < SEMANTIC_ENFORCEMENT_CONFIDENCE) {
            flags.add("SEMANTIC_GUARD_UNCERTAIN");
            return;
        }
        add(flags, decision.promptInjection(), "PROMPT_INJECTION");
        add(flags, decision.crossTenantAccess(), "CROSS_USER_ACCESS");
        add(flags, decision.sensitiveData(), "SENSITIVE_DATA");
        add(flags, decision.complaint(), "COMPLAINT");
        add(flags, decision.humanHandoff(), "HUMAN_HANDOFF_REQUEST");
        add(flags, decision.severeThreat(), "SEVERE_THREAT");
        flags.add("SEMANTIC_GUARD_APPLIED");
    }

    private void add(List<String> flags, boolean matched, String flag) {
        if (matched && !flags.contains(flag)) flags.add(flag);
    }

    /**
     * 输出阶段的最小确定性兜底。这里对手机号和卡号做脱敏；订单/退款事实一致性应由输出风控节点
     * 对照工具结果引用检查。返回空答案时给出保守话术，避免模型失败后把 null 直接暴露给客户端。
     */
    public String sanitizeOutput(String answer) {
        if (answer == null || answer.isBlank()) return "当前信息不足以给出可靠结论，我已为你保留上下文，可以继续补充信息或转人工。";
        return CARD.matcher(PHONE.matcher(answer).replaceAll("1**********")).replaceAll("****已脱敏****");
    }

    private boolean contains(String text, String... words) {
        for (String word : words) if (text.toLowerCase().contains(word.toLowerCase())) return true;
        return false;
    }
}
