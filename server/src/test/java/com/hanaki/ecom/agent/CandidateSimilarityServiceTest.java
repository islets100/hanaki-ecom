package com.hanaki.ecom.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateSimilarityServiceTest {
    @Test
    void detectsNearDuplicateChineseAnswersWithoutDependingOnWhitespace() {
        CandidateSimilarityService service = new CandidateSimilarityService();
        assertThat(service.similarity("订单仍在运输中，请耐心等待。", "订单仍在运输中，请耐心等待！"))
                .isGreaterThan(.92);
        assertThat(service.similarity("订单仍在运输中", "售后规则要求上传质量凭证"))
                .isLessThan(.30);
    }
}
