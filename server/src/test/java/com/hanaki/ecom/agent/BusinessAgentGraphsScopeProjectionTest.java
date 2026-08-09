package com.hanaki.ecom.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessAgentGraphsScopeProjectionTest {
    @Test
    void trustedStoreAndProductScopeIsPartOfEveryBusinessSubgraphInput() {
        assertThat(BusinessAgentGraphs.SUBGRAPH_INPUT_KEYS)
                .contains("channelKind", "storeId", "productId", "productName")
                .doesNotHaveDuplicates();
    }
}
