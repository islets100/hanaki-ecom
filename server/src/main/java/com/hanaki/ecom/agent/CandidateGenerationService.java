package com.hanaki.ecom.agent;

import com.hanaki.ecom.context.ContextAssemblyRequest;
import com.hanaki.ecom.context.ContextNode;
import com.hanaki.ecom.context.SkillDisclosurePhase;
import com.hanaki.ecom.context.TrustedRequestContext;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.CandidateProfile;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.domain.Domain.ModelAnswer;
import com.hanaki.ecom.domain.Domain.RiskLevel;
import com.hanaki.ecom.observability.AgentTelemetryService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个候选分支的纯生成服务。
 *
 * <p>三个候选共享同一份已经冻结并校验哈希的 {@link EvaluationContextSnapshot}，区别只来自
 * {@link CandidateProfile}：事实证据、流程边界、用户体验三个方向。候选阶段只绑定当前租户和
 * 当前用户的只读工具，不暴露退款提交、取消订单、补偿发放等写工具。因此即使模型被提示词注入，
 * 最多只能生成一份待评审草稿，不能在“比较答案”的过程中产生真实业务副作用。</p>
 *
 * <p>此服务不负责重试、超时和最终选择；这些职责由 BestOfThreeGraphService 统一编排。职责分离
 * 保证每一次模型调用都能作为独立 attempt 被统计和持久化。</p>
 */
@Service
public class CandidateGenerationService {
    private final AiModelGateway model;
    private final AgentTelemetryService telemetry;
    private final ScopedToolBindingFactory toolBindings;
    private final ModelUsageAccumulator usage;

    public CandidateGenerationService(AiModelGateway model, AgentTelemetryService telemetry,
                                      ScopedToolBindingFactory toolBindings, ModelUsageAccumulator usage) {
        this.model = model;
        this.telemetry = telemetry;
        this.toolBindings = toolBindings;
        this.usage = usage;
    }

    public GeneratedCandidate generate(EvaluationContextSnapshot snapshot, int variant,
                                       ConcurrentHashMap<String, Object> requestCache) {
        // candidateNo 与策略画像是一一映射的，禁止模型动态发明第四种未审计策略。
        return generate(snapshot, CandidateProfile.forCandidate(variant), requestCache);
    }

    /**
     * 在独立 Token 计量作用域中生成一个候选。Scope 使用 try-with-resources，确保模型调用抛异常时
     * ThreadLocal 计量上下文也会释放，不会把下一候选的 Token 记到本次 attempt。
     */
    public GeneratedCandidate generate(EvaluationContextSnapshot snapshot, CandidateProfile profile,
                                       ConcurrentHashMap<String, Object> requestCache) {
        try (ModelUsageAccumulator.Scope ignored = usage.begin()) {
            AgentDraft draft = generateDraft(snapshot, profile, requestCache);
            ModelUsageAccumulator.Usage measured = usage.snapshot();
            return new GeneratedCandidate(draft, measured.promptTokens(), measured.completionTokens());
        }
    }

    private AgentDraft generateDraft(EvaluationContextSnapshot snapshot, CandidateProfile profile,
                                     ConcurrentHashMap<String, Object> requestCache) {
        Intent intent = snapshot.intent();
        /*
         * UNKNOWN 意图只允许提出澄清问题，不需要任何工具。其它意图只暴露对应领域的最小工具集合；
         * tenantId/userId 来自冻结快照而不是模型参数，防止候选尝试查询其他用户。
         */
        String selectedSkillKey = String.valueOf(snapshot.businessFacts()
                .getOrDefault("selectedSkillKey", ""));
        OrderQueryScope orderScope = OrderQueryScope.fromBusinessFacts(snapshot.businessFacts());
        ScopedToolBindingFactory.ToolBinding scoped = intent == Intent.UNKNOWN || selectedSkillKey.isBlank()
                ? null : toolBindings.create(intent, selectedSkillKey, snapshot.tenantId(), snapshot.userId(),
                telemetry.currentTraceId(), orderScope, requestCache);
        String rewritten = String.valueOf(snapshot.businessFacts().getOrDefault("rewrittenQuery",
                snapshot.originalQuestion()));
        String traceId = telemetry.currentTraceId();
        if (traceId == null || traceId.isBlank()) traceId = snapshot.runId() + "-detached";
        RiskLevel risk = snapshot.riskTags().stream().map(this::risk).filter(value -> value != RiskLevel.LOW)
                .findFirst().orElse(RiskLevel.LOW);
        TrustedRequestContext trusted = new TrustedRequestContext(snapshot.tenantId(), snapshot.userId(),
                snapshot.conversationId(), snapshot.runId(), traceId, "", risk);
        ContextAssemblyRequest contextRequest = new ContextAssemblyRequest(trusted, intent,
                ContextNode.ANSWER_GENERATION, profile.variant(), snapshot.originalQuestion(), rewritten,
                snapshot.recentMessages(), snapshot.evidence(), snapshot.businessFacts(), List.of(),
                selectedSkillKey, selectedSkillKey.isBlank() ? SkillDisclosurePhase.NONE : SkillDisclosurePhase.SCHEMA,
                String.valueOf(snapshot.businessFacts().getOrDefault("modelVersion", "qwen-plus")), 0);
        ModelAnswer generated = model.generate(contextRequest, scoped == null ? null : scoped.schemas());
        String answer = generated.answer() == null ? "" : generated.answer().strip();
        // 模型声称引用的证据必须与冻结快照中的“标题 + 版本”精确相交，虚构引用会被直接丢弃。
        List<String> verifiedEvidence = verifiedEvidence(generated.citedEvidence(), snapshot.evidence());
        List<String> toolAudit = scoped == null ? List.of() : scoped.auditTrail();
        /*
         * safe 只是候选生成后的第一层快速标记，不等同于最终通过。随后仍会经过 CandidateHardValidator
         * 的订单归属、金额、隐私、写成功宣称等确定性校验，再由 Judge 评分。
         */
        boolean safe = !answer.isBlank()
                && !contains(answer, "AI_DASHSCOPE_API_KEY", "系统提示词如下", "tenantId=", "userId=")
                && (intent == Intent.UNKNOWN || !toolAudit.isEmpty() || !verifiedEvidence.isEmpty());
        return new AgentDraft("C" + profile.variant(), answer, verifiedEvidence, toolAudit, safe,
                clamp(generated.completeness(), 0, 15), clamp(generated.clarity(), 0, 15));
    }

    /** 候选草稿和本次真实模型用量必须一起返回，以便 attempt 级预算、审计和计费。 */
    public record GeneratedCandidate(AgentDraft draft, int promptTokens, int completionTokens) {}

    private List<String> verifiedEvidence(List<String> cited, List<KnowledgeDoc> documents) {
        if (cited == null || cited.isEmpty()) return List.of();
        Set<String> requested = new HashSet<>();
        cited.forEach(value -> requested.add(value == null ? "" : value.strip().toLowerCase(Locale.ROOT)));
        // 只返回服务端标准格式，避免把模型提供的大小写、空格或额外文本原样写入审计数据。
        return documents.stream().map(doc -> doc.title() + " " + doc.version())
                .filter(value -> requested.contains(value.toLowerCase(Locale.ROOT))).toList();
    }

    private boolean contains(String text, String... words) {
        if (text == null) return false;
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private RiskLevel risk(String value) {
        try { return RiskLevel.valueOf(value); }
        catch (Exception ignored) { return RiskLevel.LOW; }
    }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
