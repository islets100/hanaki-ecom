package com.hanaki.ecom.context;

import java.util.Set;

/**
 * 节点级上下文策略。策略决定“能加载什么”，Provider 和模型都无权扩大该集合。
 *
 * <p>平台层给出上界；Agent、节点和租户层只能继续求交集，不能把已经禁止的分区、业务字段或
 * Skill 再加回来。requiredSections 只允许落在 allowedSections 内，保证 fail-closed 节点不会
 * 在缺少实时事实或安全规则时偷偷降级为模型猜测。</p>
 */
public record ContextPolicy(
        String policyId,
        String agentType,
        ContextNode nodeCode,
        Set<ContextSectionType> allowedSections,
        Set<ContextSectionType> requiredSections,
        Set<String> allowedSkillKeys,
        Set<String> allowedBusinessFields,
        int maxInputTokens,
        int outputReserveTokens,
        int safetyReserveTokens,
        int maxRecentMessages,
        int maxRagChunks,
        int maxEpisodicMemories,
        boolean allowLongTermProfile,
        boolean allowFullSkillSchema,
        boolean requireFreshBusinessState,
        boolean failClosed,
        String policyVersion
) {
    public ContextPolicy {
        allowedSections = Set.copyOf(allowedSections);
        requiredSections = Set.copyOf(requiredSections);
        allowedSkillKeys = Set.copyOf(allowedSkillKeys);
        allowedBusinessFields = Set.copyOf(allowedBusinessFields);
        if (!allowedSections.containsAll(requiredSections))
            throw new IllegalArgumentException("requiredSections 必须是 allowedSections 的子集");
        if (!allowedSections.contains(ContextSectionType.PLATFORM_SAFETY_RULE))
            throw new IllegalArgumentException("任何模型节点都不能移除平台安全层");
        if (maxInputTokens <= 0 || outputReserveTokens < 0 || safetyReserveTokens < 0)
            throw new IllegalArgumentException("ContextPolicy Token 预算配置无效");
        if (maxRecentMessages < 0 || maxRagChunks < 0 || maxEpisodicMemories < 0)
            throw new IllegalArgumentException("ContextPolicy 条数上限不能为负数");
    }

    public boolean allows(ContextSectionType section) { return allowedSections.contains(section); }
    public boolean requires(ContextSectionType section) { return requiredSections.contains(section); }
}
