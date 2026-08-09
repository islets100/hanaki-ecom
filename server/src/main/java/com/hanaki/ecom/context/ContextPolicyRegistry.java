package com.hanaki.ecom.context;

import com.hanaki.ecom.domain.Domain.Intent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * ContextPolicy 的唯一解析入口。
 *
 * <p>当前示例项目把平台、Agent 和节点策略编译为代码中的不可变集合，部署时即可审查；未来接入
 * 配置中心或 ai_context_policy 表时，仍必须通过 {@link #restrict(ContextPolicy, ContextPolicy)}
 * 合并。restrict 只做集合交集和数值下限，因此租户覆盖只能缩小权限，不能开放平台未授权的
 * 数据分区、业务字段或 Skill，这就是 deny-wins 的工程化实现。</p>
 */
@Service
public class ContextPolicyRegistry {
    private final int configuredInputCap;
    private final int outputReserve;
    private final int safetyReserve;

    public ContextPolicyRegistry(
            @Value("${agent.context.max-prompt-tokens:6000}") int configuredInputCap,
            @Value("${agent.context.output-reserve-tokens:1200}") int outputReserve,
            @Value("${agent.context.safety-margin-tokens:800}") int safetyReserve) {
        this.configuredInputCap = configuredInputCap;
        this.outputReserve = outputReserve;
        this.safetyReserve = safetyReserve;
    }

    public ContextPolicy resolve(Intent intent, ContextNode node) {
        Intent safeIntent = intent == null ? Intent.UNKNOWN : intent;
        return switch (node) {
            case INTENT_ROUTE -> policy("route-minimal-v3", "MAIN", node,
                    sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                            ContextSectionType.AGENT_SYSTEM_PROMPT,
                            ContextSectionType.NODE_INSTRUCTION,
                            ContextSectionType.CURRENT_USER_MESSAGE,
                            ContextSectionType.RECENT_MESSAGE,
                            ContextSectionType.CONVERSATION_SUMMARY,
                            ContextSectionType.OUTPUT_CONSTRAINT),
                    sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                            ContextSectionType.AGENT_SYSTEM_PROMPT,
                            ContextSectionType.NODE_INSTRUCTION,
                            ContextSectionType.CURRENT_USER_MESSAGE,
                            ContextSectionType.OUTPUT_CONSTRAINT),
                    Set.of(), Set.of(), 4, 0, false, false, false, true);
            case QUERY_REWRITE -> policy("rewrite-minimal-v3", safeIntent.name(), node,
                    sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                            ContextSectionType.NODE_INSTRUCTION,
                            ContextSectionType.CURRENT_USER_MESSAGE,
                            ContextSectionType.RECENT_MESSAGE,
                            ContextSectionType.CONVERSATION_SUMMARY,
                            ContextSectionType.BUSINESS_STATE,
                            ContextSectionType.OUTPUT_CONSTRAINT),
                    sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                            ContextSectionType.NODE_INSTRUCTION,
                            ContextSectionType.CURRENT_USER_MESSAGE,
                            ContextSectionType.OUTPUT_CONSTRAINT),
                    Set.of(), Set.of("confirmedEntities", "businessStage"),
                    4, 0, false, false, false, true);
            case ANSWER_GENERATION -> answerPolicy(safeIntent);
            case CANDIDATE_JUDGE -> policy("judge-isolated-v3", "JUDGE", node,
                    sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                            ContextSectionType.NODE_INSTRUCTION,
                            ContextSectionType.MODEL_CANDIDATE,
                            ContextSectionType.OUTPUT_CONSTRAINT),
                    sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                            ContextSectionType.NODE_INSTRUCTION,
                            ContextSectionType.MODEL_CANDIDATE,
                            ContextSectionType.OUTPUT_CONSTRAINT),
                    Set.of(), Set.of(), 0, 0, false, false, false, true);
        };
    }

    private ContextPolicy answerPolicy(Intent intent) {
        Set<String> skills = switch (intent) {
            case PRE_SALE -> Set.of("query_product");
            case IN_SALE -> Set.of("recent_orders", "query_logistics");
            case AFTER_SALE -> Set.of("recent_orders", "preview_after_sale");
            case COMPLAINT -> Set.of("recent_orders");
            case HUMAN_SERVICE, UNKNOWN -> Set.of();
        };
        Set<String> businessFields = switch (intent) {
            case PRE_SALE, HUMAN_SERVICE, UNKNOWN -> Set.of("rewrittenQuery", "modelVersion", "selectedSkillKey",
                    "source", "version", "fetchedAt");
            case IN_SALE -> Set.of("recentOrders", "logisticsByOrder", "rewrittenQuery", "modelVersion",
                    "selectedSkillKey", "source", "version", "fetchedAt");
            case AFTER_SALE -> Set.of("recentOrders", "rewrittenQuery", "modelVersion", "selectedSkillKey",
                    "ruleVersion", "taskStatus", "source", "version", "fetchedAt");
            case COMPLAINT -> Set.of("recentOrders", "logisticsByOrder", "rewrittenQuery", "modelVersion",
                    "selectedSkillKey", "source", "version", "fetchedAt");
        };
        boolean requiresFacts = intent == Intent.IN_SALE || intent == Intent.AFTER_SALE || intent == Intent.COMPLAINT;
        Set<ContextSectionType> required = sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                ContextSectionType.AGENT_SYSTEM_PROMPT, ContextSectionType.NODE_INSTRUCTION,
                ContextSectionType.CURRENT_USER_MESSAGE, ContextSectionType.OUTPUT_CONSTRAINT);
        if (requiresFacts) {
            EnumSet<ContextSectionType> expanded = EnumSet.copyOf(required);
            expanded.add(ContextSectionType.BUSINESS_STATE);
            required = Set.copyOf(expanded);
        }
        return policy("answer-" + intent.name().toLowerCase() + "-v3", intent.name(),
                ContextNode.ANSWER_GENERATION,
                sections(ContextSectionType.PLATFORM_SAFETY_RULE,
                        ContextSectionType.AGENT_SYSTEM_PROMPT,
                        ContextSectionType.TENANT_INSTRUCTION,
                        ContextSectionType.NODE_INSTRUCTION,
                        ContextSectionType.CURRENT_USER_MESSAGE,
                        ContextSectionType.RECENT_MESSAGE,
                        ContextSectionType.CONVERSATION_SUMMARY,
                        ContextSectionType.EPISODIC_MEMORY,
                        ContextSectionType.USER_PROFILE,
                        ContextSectionType.BUSINESS_STATE,
                        ContextSectionType.RAG_EVIDENCE,
                        ContextSectionType.SKILL_CARD,
                        ContextSectionType.SKILL_SCHEMA,
                        ContextSectionType.TOOL_RESULT,
                        ContextSectionType.OUTPUT_CONSTRAINT),
                required, skills, businessFields, 6, 6,
                intent == Intent.PRE_SALE, true, requiresFacts, requiresFacts);
    }

    /**
     * 将 lowerPriority 作为更窄的覆盖层合并到 base。集合只能求交集，预算与条数只能取更小值；
     * failClosed 和 requireFreshBusinessState 使用 OR，防止下层把安全要求从 true 改回 false。
     */
    public ContextPolicy restrict(ContextPolicy base, ContextPolicy lowerPriority) {
        if (base.nodeCode() != lowerPriority.nodeCode())
            throw new IllegalArgumentException("不能合并不同 Graph 节点的 ContextPolicy");
        Set<ContextSectionType> allowed = intersection(base.allowedSections(), lowerPriority.allowedSections());
        // 平台安全层属于不可覆盖硬约束；覆盖层若删除它，直接拒绝整份配置而不是悄悄补回。
        if (!allowed.contains(ContextSectionType.PLATFORM_SAFETY_RULE))
            throw new IllegalArgumentException("覆盖策略试图删除平台安全层");
        Set<ContextSectionType> required = union(base.requiredSections(), lowerPriority.requiredSections());
        if (!allowed.containsAll(required))
            throw new IllegalArgumentException("覆盖策略禁止了上层要求的必需上下文");
        return new ContextPolicy(base.policyId() + "+" + lowerPriority.policyId(), base.agentType(),
                base.nodeCode(), allowed, required,
                intersection(base.allowedSkillKeys(), lowerPriority.allowedSkillKeys()),
                intersection(base.allowedBusinessFields(), lowerPriority.allowedBusinessFields()),
                Math.min(base.maxInputTokens(), lowerPriority.maxInputTokens()),
                Math.max(base.outputReserveTokens(), lowerPriority.outputReserveTokens()),
                Math.max(base.safetyReserveTokens(), lowerPriority.safetyReserveTokens()),
                Math.min(base.maxRecentMessages(), lowerPriority.maxRecentMessages()),
                Math.min(base.maxRagChunks(), lowerPriority.maxRagChunks()),
                Math.min(base.maxEpisodicMemories(), lowerPriority.maxEpisodicMemories()),
                base.allowLongTermProfile() && lowerPriority.allowLongTermProfile(),
                base.allowFullSkillSchema() && lowerPriority.allowFullSkillSchema(),
                base.requireFreshBusinessState() || lowerPriority.requireFreshBusinessState(),
                base.failClosed() || lowerPriority.failClosed(),
                base.policyVersion() + "+" + lowerPriority.policyVersion());
    }

    private ContextPolicy policy(String id, String agent, ContextNode node,
                                 Set<ContextSectionType> allowed, Set<ContextSectionType> required,
                                 Set<String> skills, Set<String> fields, int recent, int rag,
                                 boolean profile, boolean fullSchema, boolean fresh, boolean failClosed) {
        return new ContextPolicy(id, agent, node, allowed, required, skills, fields,
                configuredInputCap, outputReserve, safetyReserve, recent, rag, 3,
                profile, fullSchema, fresh, failClosed, "context-policy-v3");
    }

    private static Set<ContextSectionType> sections(ContextSectionType first, ContextSectionType... rest) {
        EnumSet<ContextSectionType> values = EnumSet.of(first, rest);
        return Set.copyOf(values);
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        java.util.HashSet<T> result = new java.util.HashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    private static <T> Set<T> union(Set<T> left, Set<T> right) {
        java.util.HashSet<T> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }
}
