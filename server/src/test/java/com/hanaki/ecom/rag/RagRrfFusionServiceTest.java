package com.hanaki.ecom.rag;

import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.rag.RagPipelineModels.FusionResult;
import com.hanaki.ecom.rag.RagPipelineModels.RankedCandidate;
import com.hanaki.ecom.rag.RagPipelineModels.RecallResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalBranch;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalSource;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagRrfFusionServiceTest {
    private final RagRrfFusionService fusion = new RagRrfFusionService(60, 1.15d, 1.2d, "test-rrf");

    @Test
    void shouldFuseByRankInsteadOfAddingIncomparableRawScores() {
        RecallResult recall = new RecallResult(
                List.of(candidate("A", RetrievalSource.BM25, 1, 10_000d),
                        candidate("B", RetrievalSource.BM25, 2, 5_000d)),
                List.of(candidate("B", RetrievalSource.VECTOR, 1, 0.10d)),
                List.of(), List.of(), List.of(), "source-v1");

        FusionResult result = fusion.fuse(recall, plan(), List.of(doc("A", "A正文"), doc("B", "B正文")));

        // B 在两路都有排名，应该胜过只在 BM25 中 rawScore 极大的 A。
        assertThat(result.candidates()).extracting(RankedCandidate::chunkId).containsExactly("B", "A");
        assertThat(result.candidates().getFirst().fusedScore()).isGreaterThan(result.candidates().get(1).fusedScore());
    }

    @Test
    void shouldDeduplicateDifferentChunkIdsWithIdenticalContent() {
        RecallResult recall = new RecallResult(
                List.of(candidate("A", RetrievalSource.BM25, 1, 2d),
                        candidate("A-copy", RetrievalSource.BM25, 2, 1d)),
                List.of(), List.of(), List.of(), List.of(), "source-v1");

        FusionResult result = fusion.fuse(recall, plan(),
                List.of(doc("A", "完全相同正文"), doc("A-copy", "完全相同正文")));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().chunkId()).isEqualTo("A");
    }

    private RankedCandidate candidate(String id, RetrievalSource source, int rank, double rawScore) {
        return new RankedCandidate(id, "v1", source, rank, rawScore, 0d);
    }

    private KnowledgeDoc doc(String id, String content) {
        return new KnowledgeDoc(id, "tenant-a", "AFTER_SALE", "统一标题", content, "v1", 0d);
    }

    private RetrievalPlan plan() {
        return new RetrievalPlan("AFTER_SALE", RetrievalStrategy.HYBRID,
                List.of(RetrievalBranch.BM25, RetrievalBranch.VECTOR), List.of(),
                40, 40, 160, 30, 30, 6, 1d, 1d,
                "index-v1", "policy-v1", "config-v1", false, "test");
    }
}
