package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.ModelRoute;
import com.hanaki.ecom.domain.Domain.RoutingConfidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentRouterTest {
    @Test
    void acceptsOnlyModelIntentFromTheRegisteredWhitelist() {
        AiModelGateway model = mock(AiModelGateway.class);
        when(model.route(any(), any())).thenReturn(new ModelRoute("AFTER_SALE", 0.94, "用户要求退货"));
        IntentRouter router = new IntentRouter(model, .82, .55);

        assertThat(router.route("帮我看看这个问题", List.of(), false).intent()).isEqualTo(Intent.AFTER_SALE);
    }

    @Test
    void guardrailForcesGenericHumanHandoffInsteadOfPretendingItIsComplaintDomain() {
        AiModelGateway model = mock(AiModelGateway.class);
        IntentRouter router = new IntentRouter(model, .82, .55);

        assertThat(router.route("我要人工投诉", List.of(), true).intent()).isEqualTo(Intent.HUMAN_SERVICE);
        verifyNoInteractions(model);
    }

    @Test
    void explicitComplaintAndExplicitHumanAreDifferentDeterministicRoutes() {
        AiModelGateway model = mock(AiModelGateway.class);
        IntentRouter router = new IntentRouter(model, .82, .55);

        assertThat(router.route("我要投诉商家", List.of(), false).intent()).isEqualTo(Intent.COMPLAINT);
        assertThat(router.route("请转人工客服", List.of(), false).intent()).isEqualTo(Intent.HUMAN_SERVICE);
        verifyNoInteractions(model);
    }

    @Test
    void missingOrderIdDoesNotReduceAnOtherwiseExplicitAfterSaleIntent() {
        AiModelGateway model = mock(AiModelGateway.class);
        IntentRouter router = new IntentRouter(model, .82, .55);

        var result = router.route("我要退货", List.of(), false);

        assertThat(result.intent()).isEqualTo(Intent.AFTER_SALE);
        assertThat(result.confidenceBand()).isEqualTo(RoutingConfidence.HIGH);
        assertThat(result.needClarification()).isFalse();
        assertThat(result.entities()).doesNotContainKey("orderId");
        verifyNoInteractions(model);
    }

    @Test
    void closeModelCandidatesAreCalibratedToClarificationInsteadOfDirectToolUse() {
        AiModelGateway model = mock(AiModelGateway.class);
        when(model.route(any(), any())).thenReturn(
                new ModelRoute("IN_SALE", "AFTER_SALE", .84, .80, "订单与售后边界不清"));
        IntentRouter router = new IntentRouter(model, .82, .55);

        var result = router.route("这个怎么处理", List.of(), false);

        assertThat(result.modelSelfConfidence()).isEqualTo(.84);
        assertThat(result.confidenceBand()).isEqualTo(RoutingConfidence.MEDIUM);
        assertThat(result.needClarification()).isTrue();
        assertThat(result.candidates()).containsExactly(Intent.IN_SALE, Intent.AFTER_SALE);
    }
}
