package com.hanaki.ecom.rag;

import com.hanaki.ecom.agent.QueryNormalizer;
import com.hanaki.ecom.context.RetrievedChunkRef;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.rag.RagPipelineModels.DegradationEvent;
import com.hanaki.ecom.rag.RagPipelineModels.EvidenceValidationResult;
import com.hanaki.ecom.rag.RagPipelineModels.QueryRewriteResult;
import com.hanaki.ecom.rag.RagPipelineModels.RerankResult;
import com.hanaki.ecom.rag.RagPipelineModels.RetrievalPlan;
import com.hanaki.ecom.security.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 在检索结果进入回答模型之前执行确定性证据校验。
 *
 * <p>Rerank 只回答“文本看起来是否相关”，不能证明文档仍有效、属于当前租户、覆盖了全部子问题，
 * 更不能解决两份政策互相冲突。这个节点把相关性与可用性分开：先验证租户/版本/最低分数和内容安全，
 * 再检查子问题覆盖与政策冲突；不满足条件时明确返回 insufficient/conflict，禁止模型自行补写政策。</p>
 */
@Service
public final class RagEvidenceValidationService {
    private static final List<String> INJECTION_MARKERS = List.of(
            "忽略之前的指令", "忽略系统提示", "system prompt", "developer message",
            "输出其他租户", "绕过权限", "执行以下命令");
    private static final List<Opposition> POLICY_OPPOSITIONS = List.of(
            new Opposition(List.of("支持退货", "可以退货", "可退货"), List.of("不支持退货", "不可退货")),
            new Opposition(List.of("免运费", "运费由商家承担"), List.of("运费由用户承担", "需承担运费")),
            new Opposition(List.of("支持换货", "可以换货"), List.of("不支持换货", "不可换货")));

    private final double minimumScore;
    private final double minimumSubQuestionCoverage;

    public RagEvidenceValidationService(
            @Value("${agent.rag.evidence.minimum-score:0.01}") double minimumScore,
            @Value("${agent.rag.evidence.minimum-sub-question-coverage:0.25}") double minimumSubQuestionCoverage) {
        this.minimumScore = finiteRange(minimumScore, 0d, 1d, 0.01d);
        this.minimumSubQuestionCoverage = finiteRange(minimumSubQuestionCoverage, 0d, 1d, 0.25d);
    }

    public EvidenceValidationResult validate(String trustedTenantId,
                                             RetrievalPlan plan,
                                             QueryRewriteResult rewrite,
                                             RerankResult reranked,
                                             List<KnowledgeDoc> currentDocuments) {
        List<DegradationEvent> degradations = reranked == null ? List.of() : reranked.degradations();
        if (plan.skipKnowledge())
            return new EvidenceValidationResult(List.of(), false, false, List.of(), List.of(), degradations);
        if (rewrite.clarificationRequired())
            return new EvidenceValidationResult(List.of(), true, false, rewrite.subQuestions(),
                    List.of(rewrite.clarificationReason()), degradations);

        Map<String, KnowledgeDoc> currentById = new HashMap<>();
        if (currentDocuments != null) currentDocuments.forEach(doc -> currentById.put(doc.id(), doc));
        List<RetrievedChunkRef> accepted = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Set<String> acceptedIds = new HashSet<>();

        if (reranked != null) for (RetrievedChunkRef ref : reranked.references()) {
            KnowledgeDoc doc = currentById.get(ref.chunkId());
            if (doc == null) {
                reasons.add("候选 " + ref.chunkId() + " 已不存在或不在当前文档范围");
                continue;
            }
            if (!trustedTenantId.equals(doc.tenantId())
                    && !TenantService.PLATFORM_TENANT_ID.equals(doc.tenantId())) {
                reasons.add("候选 " + ref.chunkId() + " 的租户校验失败");
                continue;
            }
            if (!doc.version().equals(ref.documentVersion())) {
                reasons.add("候选 " + ref.chunkId() + " 已发布新版本，旧引用失效");
                continue;
            }
            if (!Double.isFinite(ref.retrievalScore()) || ref.retrievalScore() < minimumScore) {
                reasons.add("候选 " + ref.chunkId() + " 低于最低证据分数");
                continue;
            }
            if (containsInjection(doc.content())) {
                reasons.add("候选 " + ref.chunkId() + " 命中提示注入特征，已隔离");
                continue;
            }
            if (acceptedIds.add(ref.chunkId())) accepted.add(ref);
        }

        // 高分在前；同分时优先版本字符串较新的文档，使结果稳定且便于回放。
        accepted.sort(Comparator.comparingDouble(RetrievedChunkRef::retrievalScore).reversed()
                .thenComparing(RetrievedChunkRef::documentVersion, Comparator.reverseOrder())
                .thenComparing(RetrievedChunkRef::chunkId));

        List<String> uncovered = uncoveredQuestions(rewrite.subQuestions(), accepted, currentById);
        boolean conflict = hasPolicyConflict(accepted, currentById);
        boolean insufficient = accepted.isEmpty()
                || (!rewrite.subQuestions().isEmpty() && uncovered.size() == rewrite.subQuestions().size());
        if (!uncovered.isEmpty()) reasons.add("部分子问题缺少直接证据：" + String.join("；", uncovered));
        if (conflict) reasons.add("高排名政策证据存在无法由确定性版本规则消解的冲突");
        if (accepted.isEmpty()) reasons.add("没有通过租户、版本、分数与内容安全校验的证据");

        /*
         * 冲突时清空 approvedReferences 是有意的：保留冲突文档让回答模型“自行判断”会把确定性
         * 政策裁决重新退化为概率选择。上层应向用户说明需核实，或转交受控规则/人工流程。
         */
        List<RetrievedChunkRef> approved = conflict ? List.of() : accepted.stream()
                .limit(plan.finalTopK()).toList();
        return new EvidenceValidationResult(approved, insufficient || conflict, conflict,
                uncovered, reasons, degradations);
    }

    private List<String> uncoveredQuestions(List<String> questions,
                                            List<RetrievedChunkRef> accepted,
                                            Map<String, KnowledgeDoc> currentById) {
        if (questions == null || questions.isEmpty()) return List.of();
        String evidence = accepted.stream().map(ref -> currentById.get(ref.chunkId()))
                .filter(doc -> doc != null).map(doc -> doc.title() + " " + doc.content())
                .reduce((left, right) -> left + " " + right).orElse("");
        Set<String> evidenceTerms = new HashSet<>(terms(evidence));
        List<String> uncovered = new ArrayList<>();
        for (String question : questions) {
            Set<String> queryTerms = new HashSet<>(terms(question));
            if (queryTerms.isEmpty()) continue;
            long matches = queryTerms.stream().filter(evidenceTerms::contains).count();
            double coverage = (double) matches / queryTerms.size();
            if (coverage < minimumSubQuestionCoverage) uncovered.add(question);
        }
        return List.copyOf(uncovered);
    }

    private boolean hasPolicyConflict(List<RetrievedChunkRef> accepted,
                                      Map<String, KnowledgeDoc> currentById) {
        // 只比较前八条强证据，避免大量弱召回中的偶然否定词制造假冲突。
        List<String> texts = accepted.stream().limit(8).map(ref -> currentById.get(ref.chunkId()))
                .filter(doc -> doc != null)
                .map(doc -> (doc.title() + " " + doc.content()).toLowerCase(Locale.ROOT)).toList();
        for (Opposition opposition : POLICY_OPPOSITIONS) {
            /*
             * “不可以退货”包含“可以退货”这个字面子串。先移除已知否定短语再匹配肯定短语，避免
             * 单份明确禁止政策被误判成同时肯定和否定。这里使用确定性词表，不交给模型猜测否定范围。
             */
            boolean positive = texts.stream().anyMatch(text -> {
                String withoutNegatives = text;
                for (String negative : opposition.negative())
                    withoutNegatives = withoutNegatives.replace(negative, " ");
                return containsAny(withoutNegatives, opposition.positive());
            });
            boolean negative = texts.stream().anyMatch(text -> containsAny(text, opposition.negative()));
            if (positive && negative) return true;
        }
        return false;
    }

    private boolean containsInjection(String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        return INJECTION_MARKERS.stream().anyMatch(marker -> normalized.contains(marker.toLowerCase(Locale.ROOT)));
    }

    private List<String> terms(String text) {
        String normalized = QueryNormalizer.normalize(text).replaceAll("[^\\p{L}\\p{N}]", "");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < normalized.length(); index++) {
            result.add(String.valueOf(normalized.charAt(index)));
            if (index + 1 < normalized.length()) result.add(normalized.substring(index, index + 2));
        }
        return result;
    }

    private boolean containsAny(String text, List<String> values) {
        return values.stream().anyMatch(text::contains);
    }

    private double finiteRange(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
    }

    private record Opposition(List<String> positive, List<String> negative) {}
}
