package com.hanaki.ecom.rag;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.rag.RagPipelineModels.DocumentType;
import com.hanaki.ecom.rag.RagPipelineModels.QueryRewriteResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalBranch;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryRouterTest {
    private final RagQueryRouter router = new RagQueryRouter(
            40, 40, 160, 30, 30, 6, 1d, 1d,
            "knowledge-2026-08", "policy-12", "rag-test-v1");

    @Test
    void shouldBuildTrustedAfterSalePlanWithBusinessFactsForOrderQuery() {
        QueryRewriteResult rewrite = rewrite(Map.of("orderId", "ORD-1"), false);

        var plan = router.route(Intent.AFTER_SALE, rewrite, false);

        assertThat(plan.strategy()).isEqualTo(RetrievalStrategy.HYBRID_WITH_BUSINESS);
        assertThat(plan.branches()).containsExactly(
                RetrievalBranch.BM25, RetrievalBranch.VECTOR,
                RetrievalBranch.RULE_ENGINE, RetrievalBranch.BUSINESS_TOOL);
        assertThat(plan.documentTypes()).contains(
                DocumentType.RETURN_POLICY, DocumentType.REPAIR_POLICY, DocumentType.COMPENSATION_POLICY);
        assertThat(plan.indexVersion()).isEqualTo("knowledge-2026-08");
        assertThat(plan.policyVersion()).isEqualTo("policy-12");
    }

    @Test
    void shouldSkipAllBranchesWhenRewriteNeedsClarification() {
        var plan = router.route(Intent.PRE_SALE, rewrite(Map.of(), true), false);

        assertThat(plan.skipKnowledge()).isTrue();
        assertThat(plan.branches()).isEmpty();
        assertThat(plan.routingReason()).contains("澄清");
    }

    private QueryRewriteResult rewrite(Map<String, String> entities, boolean clarification) {
        return new QueryRewriteResult("问题", "问题", "问题", entities, List.of("问题"),
                clarification ? .3d : .9d, clarification, clarification ? "指代不明" : "", "test", false);
    }
}
