package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.EvaluationTriggerMode;
import com.hanaki.ecom.domain.Domain.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationTriggerServiceTest {
    private final EvaluationTriggerService trigger = new EvaluationTriggerService(true, 30, 3, 18_000);

    @Test
    void supportsBothPreAndPostGenerationTriggers() {
        var pre = trigger.beforeGeneration("请同时对比规则，而且说明退款和补偿的冲突", Intent.AFTER_SALE,
                .65, 2, 1000);
        assertThat(pre.required()).isTrue();
        assertThat(pre.triggerMode()).isEqualTo(EvaluationTriggerMode.PRE_GENERATION);
        assertThat(pre.reason()).contains("MULTI_CONSTRAINT", "LOW_ROUTE_CONFIDENCE");

        AgentDraft weak = new AgentDraft("C1", "可能可以", List.of(), List.of(), true, 4, 5);
        var validation = new CandidateHardValidator.Validation(false, List.of(), List.of("UNSUPPORTED"));
        var post = trigger.afterGeneration(weak, validation, Intent.PRE_SALE, 1000);
        assertThat(post.required()).isTrue();
        assertThat(post.triggerMode()).isEqualTo(EvaluationTriggerMode.POST_GENERATION);
    }

    @Test
    void tokenBudgetDeterministicallyPreventsFanOut() {
        var decision = trigger.beforeGeneration("这是一个很长而且复杂同时还要对比的请求", Intent.PRE_SALE,
                .4, 4, 7000);
        assertThat(decision.required()).isFalse();
        assertThat(decision.reason()).isEqualTo("BUDGET_GUARD");
    }
}
