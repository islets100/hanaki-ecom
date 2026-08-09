package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.EvaluationDecision;
import com.hanaki.ecom.domain.Domain.EvaluationTriggerMode;
import com.hanaki.ecom.domain.Domain.Intent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 多候选评审的确定性触发器。模型可以提供路由置信度信号，但不能自行决定是否扩大执行成本。
 *
 * <p>PRE_GENERATION 用于请求在生成前已经表现出复杂、高风险或证据冲突；POST_GENERATION 用于
 * 单候选已经生成但硬校验、完整度、清晰度或事实支撑不足。无论哪种模式，预算守卫都先执行，
 * 防止一个超长 Prompt 被候选数量成倍放大。</p>
 */
@Service
public final class EvaluationTriggerService {
    private static final String PROFILE = "best-of-three-v2";
    private final boolean enabled;
    private final int complexLength;
    private final int candidateCount;
    private final int batchPromptBudget;

    public EvaluationTriggerService(@Value("${agent.evaluation.enabled:true}") boolean enabled,
                                    @Value("${agent.evaluation.complex-length:55}") int complexLength,
                                    @Value("${agent.evaluation.candidate-count:3}") int candidateCount,
                                    @Value("${agent.evaluation.budget.max-batch-prompt-tokens:18000}") int batchPromptBudget) {
        this.enabled = enabled;
        this.complexLength = Math.max(1, complexLength);
        this.candidateCount = Math.max(2, candidateCount);
        this.batchPromptBudget = Math.max(1, batchPromptBudget);
    }

    public EvaluationDecision beforeGeneration(String message, Intent intent, double routeConfidence,
                                               int evidenceCount, int estimatedPromptTokens) {
        // 开关和预算是最高优先级硬条件；被拦截时返回可审计原因，而不是静默跳过。
        if (!enabled) return EvaluationDecision.single("EVALUATION_DISABLED");
        if (overBudget(estimatedPromptTokens)) return EvaluationDecision.single("BUDGET_GUARD");
        List<String> reasons = new ArrayList<>();
        String value = message == null ? "" : message;
        // 多条规则可以同时命中，reason 会完整持久化，便于分析评审成本究竟由什么触发。
        if (value.length() >= complexLength) reasons.add("LONG_REQUEST");
        if (hits(value, "同时", "而且", "如果", "但是", "对比", "冲突", "综合", "分别") >= 2)
            reasons.add("MULTI_CONSTRAINT");
        if (routeConfidence < .70d) reasons.add("LOW_ROUTE_CONFIDENCE");
        if (intent == Intent.AFTER_SALE && hits(value, "退款", "退货", "换货", "补偿", "赔付") >= 2)
            reasons.add("HIGH_STAKES_AFTER_SALE");
        if (evidenceCount > 1 && hits(value, "冲突", "哪个为准", "最新规则") > 0)
            reasons.add("POSSIBLE_EVIDENCE_CONFLICT");
        if (reasons.isEmpty()) return EvaluationDecision.single("SIMPLE_REQUEST");
        return new EvaluationDecision(true, EvaluationTriggerMode.PRE_GENERATION,
                String.join(",", reasons), PROFILE);
    }

    public EvaluationDecision afterGeneration(AgentDraft draft, CandidateHardValidator.Validation validation,
                                              Intent intent, int estimatedPromptTokens) {
        // 后置触发不信任模型自报质量：同时读取确定性硬校验和服务端约束后的分项值。
        if (!enabled || overBudget(estimatedPromptTokens))
            return EvaluationDecision.single(enabled ? "BUDGET_GUARD" : "EVALUATION_DISABLED");
        List<String> reasons = new ArrayList<>();
        if (validation == null || !validation.accepted()) reasons.add("BASELINE_HARD_VALIDATION_FAILED");
        if (draft == null || draft.completeness() < 10) reasons.add("LOW_COMPLETENESS");
        if (draft == null || draft.clarity() < 8) reasons.add("LOW_CLARITY");
        if (draft != null && intent != Intent.UNKNOWN && draft.evidence().isEmpty() && draft.toolResults().isEmpty())
            reasons.add("UNSUPPORTED_BASELINE");
        if (reasons.isEmpty()) return EvaluationDecision.single("BASELINE_ACCEPTED");
        return new EvaluationDecision(true, EvaluationTriggerMode.POST_GENERATION,
                String.join(",", reasons), PROFILE);
    }

    private boolean overBudget(int estimatedPromptTokens) {
        // 估算采用最坏情况：每个候选都消耗一份完整 Prompt；Judge 预算由批次总体保护继续约束。
        return Math.max(0, estimatedPromptTokens) * candidateCount > batchPromptBudget;
    }

    private int hits(String value, String... words) {
        int count = 0;
        for (String word : words) if (value.contains(word)) count++;
        return count;
    }
}
