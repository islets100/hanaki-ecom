package com.hanaki.ecom.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.context.RetrievedChunkRef;
import com.hanaki.ecom.rag.RagPipelineModels.DegradationEvent;
import com.hanaki.ecom.rag.RagPipelineModels.DocumentType;
import com.hanaki.ecom.rag.RagPipelineModels.EvidenceValidationResult;
import com.hanaki.ecom.rag.RagPipelineModels.FusionResult;
import com.hanaki.ecom.rag.RagPipelineModels.RankedCandidate;
import com.hanaki.ecom.rag.RagPipelineModels.RecallResult;
import com.hanaki.ecom.rag.RagPipelineModels.RerankResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalBranch;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalSource;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPipelineModelsTest {

    @Test
    void shouldNormalizeEnumNamesRestoredFromGraphCheckpoint() {
        RetrievalPlan plan = plan(
                uncheckedList("BM25", "VECTOR"),
                uncheckedList("PRODUCT_MANUAL", "PRODUCT_FAQ"));

        assertThat(plan.branches()).containsExactly(RetrievalBranch.BM25, RetrievalBranch.VECTOR);
        assertThat(plan.documentTypes()).containsExactly(DocumentType.PRODUCT_MANUAL, DocumentType.PRODUCT_FAQ);
        assertThatCode(() -> new ObjectMapper().writeValueAsString(plan)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectUnknownCheckpointEnumNames() {
        assertThatThrownBy(() -> plan(
                uncheckedList("BM25", "UNTRUSTED_BRANCH"),
                uncheckedList("PRODUCT_MANUAL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RetrievalBranch");
    }

    @Test
    void shouldNormalizeNestedRecallObjectsRestoredFromGraphCheckpoint() {
        RecallResult recall = new RecallResult(
                uncheckedList(candidateMap("BM25")),
                List.of(),
                List.of(),
                List.of(),
                uncheckedList(degradationMap()),
                "source-v1");

        assertThat(recall.bm25()).singleElement().isInstanceOf(RankedCandidate.class);
        assertThat(recall.bm25().get(0).source()).isEqualTo(RetrievalSource.BM25);
        assertThat(recall.degradations()).singleElement().isInstanceOf(DegradationEvent.class);
        assertThatCode(() -> new ObjectMapper().writeValueAsString(recall)).doesNotThrowAnyException();
    }

    @Test
    void shouldNormalizeNestedFusionAndEvidenceObjectsRestoredFromGraphCheckpoint() {
        FusionResult fusion = new FusionResult(
                uncheckedList(candidateMap("VECTOR")),
                uncheckedList(degradationMap()),
                "rrf-v1");
        RerankResult rerank = new RerankResult(
                uncheckedList(referenceMap()),
                uncheckedList(degradationMap()),
                "rerank-v1");
        EvidenceValidationResult evidence = new EvidenceValidationResult(
                uncheckedList(referenceMap()),
                false,
                false,
                List.of(),
                List.of(),
                uncheckedList(degradationMap()));

        assertThat(fusion.candidates()).singleElement().isInstanceOf(RankedCandidate.class);
        assertThat(fusion.candidates().get(0).source()).isEqualTo(RetrievalSource.VECTOR);
        assertThat(rerank.references()).singleElement().isInstanceOf(RetrievedChunkRef.class);
        assertThat(evidence.approvedReferences()).singleElement().isInstanceOf(RetrievedChunkRef.class);
        assertThat(evidence.degradations()).singleElement().isInstanceOf(DegradationEvent.class);
        assertThatCode(() -> new ObjectMapper().writeValueAsString(List.of(fusion, rerank, evidence)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMalformedNestedCheckpointObjects() {
        assertThatThrownBy(() -> new RecallResult(
                uncheckedList(candidateMap("UNTRUSTED_SOURCE")),
                List.of(), List.of(), List.of(), List.of(), "source-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RetrievalSource");
    }

    private RetrievalPlan plan(List<RetrievalBranch> branches, List<DocumentType> documentTypes) {
        return new RetrievalPlan("PRE_SALE", RetrievalStrategy.HYBRID, branches, documentTypes,
                40, 40, 160, 30, 30, 6, 1d, 1d,
                "index-v1", "policy-v1", "config-v1", false, "test");
    }

    private static Map<String, Object> candidateMap(String source) {
        return Map.of(
                "chunkId", "chunk-1",
                "documentVersion", "doc-v1",
                "source", source,
                "rank", 1,
                "rawScore", 0.8d,
                "fusedScore", 0.4d);
    }

    private static Map<String, Object> degradationMap() {
        return Map.of("stage", "VECTOR", "code", "TIMEOUT", "detail", "fallback used");
    }

    private static Map<String, Object> referenceMap() {
        return Map.of("chunkId", "chunk-1", "documentVersion", "doc-v1", "retrievalScore", 0.91d);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> List<T> uncheckedList(Object... values) {
        return (List) List.of(values);
    }
}
