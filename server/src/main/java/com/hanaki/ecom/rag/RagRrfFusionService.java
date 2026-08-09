package com.hanaki.ecom.rag;

import com.hanaki.ecom.agent.CacheKeyBuilder;
import com.hanaki.ecom.agent.QueryNormalizer;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.rag.RagPipelineModels.FusionResult;
import com.hanaki.ecom.rag.RagPipelineModels.RankedCandidate;
import com.hanaki.ecom.rag.RagPipelineModels.RecallResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用加权 Reciprocal Rank Fusion 融合多个异构召回源。
 *
 * <p>BM25 分数取决于词频和索引统计，向量分数取决于模型及相似度度量，规则分支又可能只有
 * 0/1 或优先级分值，这些原始分数没有共同量纲，绝不能直接相加。本服务只使用每个分支内的
 * rank，通过 weight / (k + rank) 计算贡献，从数学上避免某一路因分值范围较大而垄断结果。</p>
 */
@Service
public final class RagRrfFusionService {
    private final int rrfK;
    private final double ruleWeight;
    private final double businessWeight;
    private final String algorithmVersion;

    public RagRrfFusionService(
            @Value("${agent.rag.fusion.rrf-k:${agent.rag.rrf-k:60}}") int rrfK,
            @Value("${agent.rag.fusion.rule-weight:1.15}") double ruleWeight,
            @Value("${agent.rag.fusion.business-weight:1.20}") double businessWeight,
            @Value("${agent.rag.fusion.version:weighted-rrf-v1}") String algorithmVersion) {
        this.rrfK = Math.max(1, rrfK);
        this.ruleWeight = positive(ruleWeight);
        this.businessWeight = positive(businessWeight);
        this.algorithmVersion = algorithmVersion == null ? "weighted-rrf-v1" : algorithmVersion;
    }

    public FusionResult fuse(RecallResult recall, RetrievalPlan plan, List<KnowledgeDoc> currentDocuments) {
        if (recall == null || plan.skipKnowledge())
            return new FusionResult(List.of(), recall == null ? List.of() : recall.degradations(), algorithmVersion);

        Map<String, KnowledgeDoc> byId = new HashMap<>();
        if (currentDocuments != null) currentDocuments.forEach(doc -> byId.put(doc.id(), doc));
        Map<String, Double> fused = new HashMap<>();
        Map<String, RankedCandidate> representative = new HashMap<>();

        contribute(recall.bm25(), plan.lexicalWeight(), fused, representative);
        contribute(recall.vector(), plan.vectorWeight(), fused, representative);
        contribute(recall.business(), businessWeight, fused, representative);
        contribute(recall.rules(), ruleWeight, fused, representative);

        List<RankedCandidate> ranked = fused.entrySet().stream()
                .map(entry -> representative.get(entry.getKey()).withFusedScore(entry.getValue()))
                .sorted(Comparator.comparingDouble(RankedCandidate::fusedScore).reversed()
                        .thenComparing(RankedCandidate::chunkId))
                .toList();

        /*
         * 第一层按 chunkId 去重；第二层按“标题 + 规范化正文”的摘要去重。后一层用于处理同一段内容
         * 因切片或迁移被写成不同 ID 的情况。摘要只在当前进程内计算，不把正文写进 Graph State。
         */
        Map<String, RankedCandidate> uniqueByContent = new LinkedHashMap<>();
        for (RankedCandidate candidate : ranked) {
            KnowledgeDoc doc = byId.get(candidate.chunkId());
            String canonical = doc == null
                    ? "missing|" + candidate.chunkId()
                    : CacheKeyBuilder.digest(QueryNormalizer.normalize(doc.title()) + "|"
                    + QueryNormalizer.normalize(doc.content()));
            uniqueByContent.putIfAbsent(canonical, candidate);
            if (uniqueByContent.size() >= plan.fusionTopK()) break;
        }
        return new FusionResult(new ArrayList<>(uniqueByContent.values()), recall.degradations(), algorithmVersion);
    }

    private void contribute(List<RankedCandidate> candidates, double weight,
                            Map<String, Double> fused,
                            Map<String, RankedCandidate> representative) {
        for (RankedCandidate candidate : candidates) {
            if (candidate.chunkId().isBlank()) continue;
            double contribution = weight / (rrfK + candidate.rank());
            fused.merge(candidate.chunkId(), contribution, Double::sum);
            representative.merge(candidate.chunkId(), candidate, (left, right) ->
                    sourcePriority(right.source()) > sourcePriority(left.source()) ? right : left);
        }
    }

    private int sourcePriority(RetrievalSource source) {
        return switch (source) {
            case BUSINESS_TOOL -> 4;
            case RULE_ENGINE -> 3;
            case BM25 -> 2;
            case VECTOR -> 1;
            case RRF_FALLBACK -> 0;
        };
    }

    private double positive(double value) {
        return Double.isFinite(value) && value > 0d ? value : 1d;
    }
}
