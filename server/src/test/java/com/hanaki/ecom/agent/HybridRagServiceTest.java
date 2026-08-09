package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.rag.RagEvidenceValidationService;
import com.hanaki.ecom.rag.RagPipelineModels.DegradationEvent;
import com.hanaki.ecom.rag.RagPipelineModels.RankedCandidate;
import com.hanaki.ecom.rag.RagPipelineModels.RecallResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalBranch;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalSource;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalStrategy;
import com.hanaki.ecom.rag.RagRrfFusionService;
import com.hanaki.ecom.store.EcommerceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HybridRagServiceTest {

    @Test
    void degradedRecallRemainsValidForCurrentRequestWhenCandidatesAreTenantSafe() {
        HybridRagService service = new HybridRagService(
                mock(EcommerceStore.class),
                mock(VersionedAgentCache.class),
                mock(EmbeddingCacheService.class),
                mock(AgentTelemetryService.class),
                mock(DashScopeRerankClient.class),
                mock(ElasticsearchKnowledgeIndex.class),
                mock(RagRrfFusionService.class),
                mock(RagEvidenceValidationService.class),
                1_000L,
                "qwen3-rerank");

        KnowledgeDoc document = new KnowledgeDoc(
                "doc-1", "merchant-trail", "product", "徒步鞋", "商品介绍", "v1", 0d);
        RankedCandidate candidate = new RankedCandidate(
                "doc-1", "v1", RetrievalSource.BM25, 1, 1d, 0d);
        RecallResult recall = new RecallResult(
                List.of(candidate),
                List.of(),
                List.of(),
                List.of(),
                List.of(new DegradationEvent("RECALL", "VECTOR_UNAVAILABLE", "fallback")),
                "source-v1");
        RetrievalPlan plan = new RetrievalPlan(
                "product", RetrievalStrategy.HYBRID,
                List.of(RetrievalBranch.BM25, RetrievalBranch.VECTOR), List.of(),
                10, 10, 20, 10, 10, 5,
                1d, 1d, "index-v1", "policy-v1", "config-v1", false, "test");

        assertThat(service.validRecall(recall, "merchant-trail",
                Map.of(document.id(), document), plan, "source-v1")).isTrue();
        assertThat(service.validRecall(recall, "merchant-living",
                Map.of(document.id(), document), plan, "source-v1")).isFalse();

        KnowledgeDoc platformDocument = new KnowledgeDoc(
                "platform-policy", "platform", "COMMON", "平台规则", "公共售后规则", "v2", 0d);
        RecallResult platformRecall = new RecallResult(
                List.of(new RankedCandidate("platform-policy", "v2", RetrievalSource.BM25, 1, 1d, 0d)),
                List.of(), List.of(), List.of(), List.of(), "source-v2");
        assertThat(service.validRecall(platformRecall, "merchant-trail",
                Map.of(platformDocument.id(), platformDocument), plan, "source-v2")).isTrue();
    }
}
