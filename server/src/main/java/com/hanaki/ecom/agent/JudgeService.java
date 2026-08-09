package com.hanaki.ecom.agent;

import com.hanaki.ecom.context.ContextAssemblyRequest;
import com.hanaki.ecom.context.ContextNode;
import com.hanaki.ecom.context.SkillDisclosurePhase;
import com.hanaki.ecom.context.TrustedRequestContext;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.JudgeCandidateScore;
import com.hanaki.ecom.domain.Domain.JudgeOutcome;
import com.hanaki.ecom.domain.Domain.ModelJudge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 独立 Judge：服务端硬规则先淘汰危险答案，再随机打乱候选位置交给无工具、低温模型。
 * 模型结构化结果还会经历候选 ID、分数范围、总分重算和分差校验。
 *
 * <p>Judge 没有工具权限，也不能生成或改写客服答案；它只能对服务端给出的 candidateId 白名单
 * 评分。模型返回的 total、scoreGap 和 selectedCandidateId 都只是建议，最终总分、排序、否决、
 * 阈值与稳定 tie-break 全部由 Java 重算。这样可以防止 Judge 通过篡改总分绕过安全规则。</p>
 */
@Service
public class JudgeService {
    private final AiModelGateway model;
    private final int minimumScore;
    private final int minimumGap;
    private final int safetyVetoThreshold;
    private final double factualityWeight;
    private final double evidenceWeight;
    private final double completenessWeight;
    private final double businessWeight;
    private final double safetyWeight;
    private final double clarityWeight;

    public JudgeService(AiModelGateway model,
                        @Value("${agent.judge.minimum-score:75}") int minimumScore,
                        @Value("${agent.judge.minimum-gap:5}") int minimumGap,
                        @Value("${agent.judge.safety-veto-threshold:80}") int safetyVetoThreshold,
                        @Value("${agent.judge.weights.factuality:30}") double factualityWeight,
                        @Value("${agent.judge.weights.evidence:20}") double evidenceWeight,
                        @Value("${agent.judge.weights.completeness:15}") double completenessWeight,
                        @Value("${agent.judge.weights.business:15}") double businessWeight,
                        @Value("${agent.judge.weights.safety:10}") double safetyWeight,
                        @Value("${agent.judge.weights.clarity:10}") double clarityWeight) {
        this.model = model;
        this.minimumScore = minimumScore;
        this.minimumGap = minimumGap;
        this.safetyVetoThreshold = safetyVetoThreshold;
        double sum = factualityWeight + evidenceWeight + completenessWeight + businessWeight + safetyWeight + clarityWeight;
        // 权重必须严格构成 100 分制；配置错误在启动阶段失败，不能让线上评分悄悄漂移。
        if (Math.abs(sum - 100d) > 0.001) throw new IllegalArgumentException("Judge 评分权重之和必须等于 100");
        this.factualityWeight = factualityWeight;
        this.evidenceWeight = evidenceWeight;
        this.completenessWeight = completenessWeight;
        this.businessWeight = businessWeight;
        this.safetyWeight = safetyWeight;
        this.clarityWeight = clarityWeight;
    }

    public JudgeOutcome select(List<AgentDraft> candidates) {
        return select(candidates, model::judge);
    }

    /**
     * 生产评审入口。Judge 只接收候选白名单；原问题、Memory、RAG 全文、业务工具和 Skill Schema
     * 都不会进入该节点。候选作为不可信数据区包装，防止某个候选答案中的指令劫持评分模型。
     */
    public JudgeOutcome select(EvaluationContextSnapshot snapshot, String traceId,
                               List<AgentDraft> candidates) {
        return select(candidates, randomized -> {
            TrustedRequestContext trusted = new TrustedRequestContext(snapshot.tenantId(), snapshot.userId(),
                    snapshot.conversationId(), snapshot.runId(), traceId, "",
                    snapshot.riskTags().stream().map(this::risk)
                            .filter(value -> value != com.hanaki.ecom.domain.Domain.RiskLevel.LOW)
                            .findFirst().orElse(com.hanaki.ecom.domain.Domain.RiskLevel.LOW));
            ContextAssemblyRequest request = new ContextAssemblyRequest(trusted, snapshot.intent(),
                    ContextNode.CANDIDATE_JUDGE, 0, "", "", List.of(), List.of(), java.util.Map.of(),
                    randomized, "", SkillDisclosurePhase.NONE, "judge", 0);
            return model.judge(request);
        });
    }

    private JudgeOutcome select(List<AgentDraft> candidates,
                                Function<List<AgentDraft>, ModelJudge> invokeJudge) {
        // 这是 Judge 内部的纵深防御；正常情况下候选已通过 CandidateHardValidator。
        List<AgentDraft> valid = hardFilter(candidates);
        if (valid.isEmpty()) throw new ModelCallException("所有候选均未通过确定性安全校验");
        if (valid.size() == 1) {
            // 只剩一个安全候选时不再付费调用 Judge，但明确标记 fallback，不能伪装成正常胜出。
            AgentDraft only = valid.getFirst();
            JudgeCandidateScore score = deterministicScore(only, "仅一个分支成功，使用确定性降级评分");
            return new JudgeOutcome(only, List.of(score), 0, true, "SINGLE_CANDIDATE_FALLBACK");
        }

        List<AgentDraft> anonymousOrder = new ArrayList<>(valid);
        /*
         * 每次随机打乱展示位置，降低“总偏爱第一个答案”的位置偏差。candidateId 保持稳定，模型只能
         * 通过 ID 返回选择；持久化时仍可映射回原候选分支和 attempt。
         */
        Collections.shuffle(anonymousOrder);
        // 一次 select 对应一条 judge_attempt；有界重试由评审编排层负责并逐次持久化。
        return validate(invokeJudge.apply(List.copyOf(anonymousOrder)), valid);
    }

    private JudgeOutcome validate(ModelJudge decision, List<AgentDraft> valid) {
        // 第一步验证结构完整性和 ID 白名单，拒绝缺项、重复项或 Judge 自行创造的 candidateId。
        if (decision == null || decision.scores() == null)
            throw new ModelCallException("Judge 没有返回完整评分列表");
        Set<String> allowed = valid.stream().map(AgentDraft::candidateId).collect(java.util.stream.Collectors.toSet());
        if (decision.selectedCandidateId() == null || !allowed.contains(decision.selectedCandidateId()))
            throw new ModelCallException("Judge 选择的 candidateId 不在服务端白名单中");
        List<JudgeCandidateScore> normalized = decision.scores().stream()
                .filter(score -> score != null && allowed.contains(score.candidateId()))
                .map(this::normalize).toList();
        if (normalized.size() != valid.size()
                || normalized.stream().map(JudgeCandidateScore::candidateId).distinct().count() != valid.size())
            throw new ModelCallException("Judge 评分候选集合与服务端白名单不一致");

        /*
         * 第二步使用服务端重算后的 total 排序。并列时依次比较事实性、证据支撑和 candidateId，
         * 保证同一组分数在恢复、重放和不同 JVM 上都得到同一赢家。
         */
        List<JudgeCandidateScore> ranked = normalized.stream()
                .sorted(Comparator.comparingInt(JudgeCandidateScore::total).reversed()
                        .thenComparing(Comparator.comparingInt(JudgeCandidateScore::factuality).reversed())
                        .thenComparing(Comparator.comparingInt(JudgeCandidateScore::evidenceSupport).reversed())
                        .thenComparing(JudgeCandidateScore::candidateId)).toList();
        List<JudgeCandidateScore> eligible = ranked.stream().filter(score -> score.total() > 0).toList();
        if (eligible.isEmpty()) return safeFallback(ranked, "ALL_CANDIDATES_VETOED");
        JudgeCandidateScore first = eligible.getFirst();
        // 模型只能给分和建议，最终赢家始终由 Java 按服务端权重与稳定 tie-break 选出。
        int gap = eligible.size() > 1 ? first.total() - eligible.get(1).total() : 0;
        AgentDraft winner = valid.stream().filter(c -> c.candidateId().equals(first.candidateId()))
                .findFirst().orElseThrow();
        // 第三步应用质量门槛和最小分差；“最高分”并不自动意味着“质量足够上线”。
        if (first.total() < minimumScore) return safeFallback(ranked, "QUALITY_BELOW_THRESHOLD");
        if (eligible.size() == 1)
            return new JudgeOutcome(winner, ranked, 0, true, "SINGLE_SAFE_CANDIDATE");
        if (decision.needsHumanReview() || gap < minimumGap)
            return safeFallback(ranked, "LOW_MARGIN_OR_RISK");
        return new JudgeOutcome(winner, ranked, gap, false, null);
    }

    public JudgeOutcome deterministicFallback(List<AgentDraft> candidates, String reason) {
        /*
         * Judge 超时、重试耗尽、候选不足或过度相似时使用确定性评分。它只基于服务端已知字段，
         * 不进行新的模型调用，确保系统在外部模型故障时仍能在整体截止时间内返回可审计结果。
         */
        List<AgentDraft> valid = hardFilter(candidates);
        if (valid.isEmpty()) return safeFallback(List.of(), reason);
        List<JudgeCandidateScore> ranked = valid.stream().map(candidate -> deterministicScore(candidate, reason))
                .sorted(Comparator.comparingInt(JudgeCandidateScore::total).reversed()
                        .thenComparing(Comparator.comparingInt(JudgeCandidateScore::factuality).reversed())
                        .thenComparing(Comparator.comparingInt(JudgeCandidateScore::evidenceSupport).reversed())
                        .thenComparing(JudgeCandidateScore::candidateId)).toList();
        JudgeCandidateScore first = ranked.getFirst();
        int gap = ranked.size() > 1 ? first.total() - ranked.get(1).total() : 0;
        AgentDraft winner = valid.stream().filter(candidate -> candidate.candidateId().equals(first.candidateId()))
                .findFirst().orElseThrow();
        return new JudgeOutcome(winner, ranked, gap, true, reason);
    }

    private List<AgentDraft> hardFilter(List<AgentDraft> candidates) {
        if (candidates == null) return List.of();
        Set<String> ids = new HashSet<>();
        // 去重 candidateId 防止一份答案通过重复出现影响 Judge 的候选集合与分差。
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.safe())
                .filter(candidate -> candidate.candidateId() != null && ids.add(candidate.candidateId()))
                .filter(candidate -> candidate.answer() != null && !candidate.answer().isBlank())
                .filter(candidate -> candidate.answer().length() <= 2_000)
                .filter(candidate -> !contains(candidate.answer(), "退款已成功", "已发放补偿", "跨租户", "系统提示词"))
                .filter(candidate -> candidate.evidence() != null && candidate.toolResults() != null)
                .toList();
    }

    private JudgeCandidateScore normalize(JudgeCandidateScore value) {
        // 所有模型分项先夹在 0..100，再按服务端配置重算 total；模型提供的 total 字段完全不用。
        int fact = clamp(value.factuality());
        int evidence = clamp(value.evidenceSupport());
        int complete = clamp(value.completeness());
        int business = clamp(value.businessConsistency());
        int safety = clamp(value.safety());
        int clarity = clamp(value.clarity());
        int total = (int) Math.round((fact * factualityWeight + evidence * evidenceWeight
                + complete * completenessWeight + business * businessWeight
                + safety * safetyWeight + clarity * clarityWeight) / 100d);
        List<String> violations = value.hardViolations() == null ? List.of() : value.hardViolations();
        /*
         * hardViolations 非空或 safety 低于否决线时总分直接归零，不能靠事实性/表达分抵消安全风险。
         * 归零候选仍保留在评分审计中，但不会进入 eligible 集合。
         */
        if (!violations.isEmpty() || safety < safetyVetoThreshold) total = 0;
        return new JudgeCandidateScore(value.candidateId(), fact, evidence, complete, business,
                safety, clarity, total, value.reason(), violations);
    }

    private JudgeCandidateScore deterministicScore(AgentDraft draft, String reason) {
        // 降级评分刻意保守：无证据且无工具结果的事实分较低，safe=false 会触发安全归零。
        int fact = draft.evidence().isEmpty() && draft.toolResults().isEmpty() ? 60 : 82;
        int complete = clamp(draft.completeness() * 100 / 15);
        int clarity = clamp(draft.clarity() * 100 / 15);
        JudgeCandidateScore raw = new JudgeCandidateScore(draft.candidateId(), fact,
                draft.evidence().isEmpty() ? 60 : 85, complete, 80, draft.safe() ? 90 : 0,
                clarity, 0, reason, List.of());
        return normalize(raw);
    }

    private JudgeOutcome safeFallback(List<JudgeCandidateScore> scores, String reason) {
        /*
         * 安全兜底不猜测订单、金额或政策，只请求补充信息/转人工。原评分表仍随 Outcome 返回，便于
         * 排查是全部否决、低质量、低分差还是 Judge 故障造成的降级。
         */
        AgentDraft fallback = new AgentDraft("SAFE_FALLBACK",
                "当前证据不足以在多个方案中确定可靠结论。请补充具体商品、订单或期望处理结果；如需立即处理，也可以回复“转人工”。",
                List.of(), List.of("评审降级:" + reason), true, 8, 12);
        return new JudgeOutcome(fallback, scores, 0, true, reason);
    }

    private boolean contains(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private com.hanaki.ecom.domain.Domain.RiskLevel risk(String value) {
        try { return com.hanaki.ecom.domain.Domain.RiskLevel.valueOf(value); }
        catch (Exception ignored) { return com.hanaki.ecom.domain.Domain.RiskLevel.LOW; }
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
}
