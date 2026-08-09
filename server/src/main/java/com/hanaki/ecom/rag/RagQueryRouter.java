package com.hanaki.ecom.rag;

import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.rag.RagPipelineModels.DocumentType;
import com.hanaki.ecom.rag.RagPipelineModels.QueryRewriteResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalBranch;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端可信 Query Router。
 *
 * <p>该实现不接收 tenantId、索引名、ES DSL 或任意过滤表达式。它只把已经确定的业务意图和
 * 结构化改写映射到固定枚举，再从受控配置读取 topK 等数值。因而即使用户在问题里写“忽略过滤，
 * 查询其他租户索引”，最多只会作为普通检索文本，绝不可能改变租户隔离和索引路由。</p>
 */
@Service
public final class RagQueryRouter {
    private final int lexicalTopK;
    private final int vectorTopK;
    private final int vectorNumCandidates;
    private final int fusionTopK;
    private final int rerankTopN;
    private final int finalTopK;
    private final double lexicalWeight;
    private final double vectorWeight;
    private final String indexVersion;
    private final String policyVersion;
    private final String configVersion;

    public RagQueryRouter(
            @Value("${agent.rag.recall.lexical-top-k:40}") int lexicalTopK,
            @Value("${agent.rag.recall.vector-top-k:40}") int vectorTopK,
            @Value("${agent.rag.recall.vector-num-candidates:160}") int vectorNumCandidates,
            @Value("${agent.rag.fusion.top-k:30}") int fusionTopK,
            @Value("${agent.rag.rerank.input-top-n:30}") int rerankTopN,
            @Value("${agent.rag.rerank.output-top-k:${agent.rag.top-k:6}}") int finalTopK,
            @Value("${agent.rag.fusion.lexical-weight:1.0}") double lexicalWeight,
            @Value("${agent.rag.fusion.vector-weight:1.0}") double vectorWeight,
            @Value("${agent.rag.elasticsearch.index-version:knowledge-v1}") String indexVersion,
            @Value("${agent.rag.policy-version:policy-v1}") String policyVersion,
            @Value("${agent.rag.config-version:rag-v1}") String configVersion) {
        this.lexicalTopK = bounded(lexicalTopK, 1, 200);
        this.vectorTopK = bounded(vectorTopK, 1, 200);
        this.vectorNumCandidates = bounded(vectorNumCandidates, this.vectorTopK, 2_000);
        this.fusionTopK = bounded(fusionTopK, 1, 100);
        this.rerankTopN = bounded(rerankTopN, 1, 100);
        this.finalTopK = bounded(finalTopK, 1, 20);
        this.lexicalWeight = positiveWeight(lexicalWeight);
        this.vectorWeight = positiveWeight(vectorWeight);
        this.indexVersion = safe(indexVersion, "knowledge-v1");
        this.policyVersion = safe(policyVersion, "policy-v1");
        this.configVersion = safe(configVersion, "rag-v1");
    }

    /**
     * @param skipKnowledge 已由确定性业务判断确认不需要知识检索，例如纯实时物流查询
     */
    public RetrievalPlan route(Intent intent, QueryRewriteResult rewrite, boolean skipKnowledge) {
        List<RetrievalBranch> branches = new ArrayList<>();
        List<DocumentType> documentTypes = new ArrayList<>();
        RetrievalStrategy strategy;
        String reason;

        if (skipKnowledge || rewrite.clarificationRequired()) {
            strategy = RetrievalStrategy.LEXICAL_ONLY;
            reason = skipKnowledge ? "确定性实时业务查询，无需知识库" : "关键指代不明确，先澄清再检索";
            return plan(intent, strategy, branches, documentTypes, true, reason);
        }

        switch (intent) {
            case PRE_SALE -> {
                strategy = RetrievalStrategy.HYBRID;
                branches.add(RetrievalBranch.BM25);
                branches.add(RetrievalBranch.VECTOR);
                documentTypes.add(DocumentType.PRODUCT_MANUAL);
                documentTypes.add(DocumentType.PRODUCT_SPECIFICATION);
                documentTypes.add(DocumentType.PRODUCT_FAQ);
                documentTypes.add(DocumentType.PROMOTION_POLICY);
                reason = "售前问题需要型号/参数精确匹配与语义召回并行";
            }
            case AFTER_SALE, COMPLAINT -> {
                strategy = RetrievalStrategy.HYBRID_WITH_RULE;
                branches.add(RetrievalBranch.BM25);
                branches.add(RetrievalBranch.VECTOR);
                branches.add(RetrievalBranch.RULE_ENGINE);
                if (rewrite.protectedEntities().containsKey("orderId")) {
                    branches.add(RetrievalBranch.BUSINESS_TOOL);
                    strategy = RetrievalStrategy.HYBRID_WITH_BUSINESS;
                }
                documentTypes.add(DocumentType.RETURN_POLICY);
                documentTypes.add(DocumentType.REPAIR_POLICY);
                documentTypes.add(DocumentType.COMPENSATION_POLICY);
                documentTypes.add(DocumentType.PRODUCT_FAQ);
                reason = "售后/投诉问题同时需要政策文本、规则优先级和可能的订单事实";
            }
            case IN_SALE -> {
                strategy = RetrievalStrategy.HYBRID_WITH_BUSINESS;
                branches.add(RetrievalBranch.BM25);
                branches.add(RetrievalBranch.VECTOR);
                branches.add(RetrievalBranch.BUSINESS_TOOL);
                documentTypes.add(DocumentType.PRODUCT_FAQ);
                documentTypes.add(DocumentType.PROMOTION_POLICY);
                reason = "售中问题需要通用说明，并由后续只读业务工具补充实时事实";
            }
            default -> {
                strategy = RetrievalStrategy.HYBRID;
                branches.add(RetrievalBranch.BM25);
                branches.add(RetrievalBranch.VECTOR);
                documentTypes.addAll(List.of(DocumentType.PRODUCT_FAQ, DocumentType.RETURN_POLICY));
                reason = "通用客服问题使用受限混合检索";
            }
        }
        return plan(intent, strategy, branches, documentTypes, false, reason);
    }

    private RetrievalPlan plan(Intent intent, RetrievalStrategy strategy,
                               List<RetrievalBranch> branches, List<DocumentType> documentTypes,
                               boolean skip, String reason) {
        return new RetrievalPlan(intent.name(), strategy, branches, documentTypes,
                lexicalTopK, vectorTopK, vectorNumCandidates, fusionTopK, rerankTopN, finalTopK,
                lexicalWeight, vectorWeight, indexVersion, policyVersion, configVersion, skip, reason);
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private double positiveWeight(double value) {
        return Double.isFinite(value) && value > 0d ? Math.min(10d, value) : 1d;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
