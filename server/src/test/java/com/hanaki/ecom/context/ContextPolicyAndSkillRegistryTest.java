package com.hanaki.ecom.context;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextPolicyAndSkillRegistryTest {
    private final ContextPolicyRegistry policies = new ContextPolicyRegistry(6_000, 1_200, 800);

    @Test
    void tenantRestrictionCannotExpandSkillsOrRemovePlatformSafety() {
        ContextPolicy base = policies.resolve(Intent.AFTER_SALE, ContextNode.ANSWER_GENERATION);
        ContextPolicy narrower = new ContextPolicy("tenant-restriction", "AFTER_SALE",
                ContextNode.ANSWER_GENERATION,
                Set.of(ContextSectionType.PLATFORM_SAFETY_RULE, ContextSectionType.AGENT_SYSTEM_PROMPT,
                        ContextSectionType.NODE_INSTRUCTION, ContextSectionType.CURRENT_USER_MESSAGE,
                        ContextSectionType.BUSINESS_STATE, ContextSectionType.OUTPUT_CONSTRAINT,
                        ContextSectionType.SKILL_SCHEMA),
                Set.of(ContextSectionType.PLATFORM_SAFETY_RULE, ContextSectionType.BUSINESS_STATE),
                Set.of("recent_orders", "submit_refund"), Set.of("recentOrders", "source", "version", "fetchedAt"),
                3_000, 1_500, 900, 2, 0, 0, false, true, true, true, "tenant-v1");

        ContextPolicy merged = policies.restrict(base, narrower);

        assertThat(merged.allowedSkillKeys()).containsExactly("recent_orders");
        assertThat(merged.maxInputTokens()).isEqualTo(3_000);
        assertThat(merged.requiredSections()).contains(ContextSectionType.PLATFORM_SAFETY_RULE,
                ContextSectionType.BUSINESS_STATE);
    }

    @Test
    void highRiskWriteSkillCannotBeAuthorizedByAnswerNode() {
        ProgressiveSkillRegistry skills = new ProgressiveSkillRegistry();
        ContextPolicy policy = policies.resolve(Intent.AFTER_SALE, ContextNode.ANSWER_GENERATION);

        assertThatThrownBy(() -> skills.authorize(Intent.AFTER_SALE, policy, "submit_refund",
                SkillDisclosurePhase.SCHEMA, RiskLevel.LOW))
                .isInstanceOf(SecurityException.class).hasMessageContaining("未授权 Skill");
    }

    @Test
    void logisticsSchemaIncludesOnlyItsReadOnlyDependency() {
        ProgressiveSkillRegistry skills = new ProgressiveSkillRegistry();
        ContextPolicy policy = policies.resolve(Intent.IN_SALE, ContextNode.ANSWER_GENERATION);

        ProgressiveSkillRegistry.AuthorizedSkillSet authorized = skills.authorize(Intent.IN_SALE, policy,
                "query_logistics", SkillDisclosurePhase.SCHEMA, RiskLevel.LOW);

        assertThat(authorized.skillKeys()).containsExactly("recent_orders", "query_logistics");
    }
}
