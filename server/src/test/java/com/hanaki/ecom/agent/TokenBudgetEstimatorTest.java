package com.hanaki.ecom.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetEstimatorTest {
    private final TokenBudgetEstimator estimator = new TokenBudgetEstimator();

    @Test
    void estimatesChineseMoreConservativelyAndNeverBreaksSurrogatePair() {
        assertThat(estimator.estimate("这是中文客服上下文")).isGreaterThan(estimator.estimate("hello world"));
        String truncated = estimator.truncate("订单说明😀后续内容", 5);
        assertThat(truncated).doesNotEndWith("\uD83D");
        assertThat(estimator.estimate(truncated)).isLessThanOrEqualTo(5);
    }
}
