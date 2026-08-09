package com.hanaki.ecom.memory.application;

import com.hanaki.ecom.agent.TokenBudgetEstimator;
import com.hanaki.ecom.memory.api.MemoryContextQuery;
import com.hanaki.ecom.memory.api.MemoryContextResult;
import com.hanaki.ecom.memory.api.MemoryManifestItem;
import com.hanaki.ecom.memory.domain.MemoryLayer;
import com.hanaki.ecom.memory.domain.MemorySegment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四层 Memory 的唯一上下文组装器。
 *
 * <p>本类不访问 Redis、MySQL 或 Elasticsearch。上游适配器把候选数据映射为 MemorySegment，
 * Builder 再统一执行租户校验、Prompt 注入检测、TTL、去重、按层预算和效用排序。这样任何新增
 * 存储都无法绕过同一套治理逻辑。</p>
 *
 * <p>工作记忆由 Graph State 直接传给模型节点，所以这里通常只处理后三层。Token 不足时按整条
 * Memory 淘汰，不从中间截断；否则结构化摘要或事件的限定条件可能被截掉，反而制造错误事实。</p>
 */
@Service
public final class MemoryContextBuilder {
    private final TokenBudgetEstimator tokens;
    private final MemoryPolicyEngine policy;

    public MemoryContextBuilder(TokenBudgetEstimator tokens, MemoryPolicyEngine policy) {
        this.tokens = tokens;
        this.policy = policy;
    }

    public MemoryContextResult build(MemoryContextQuery query, List<MemorySegment> candidates,
                                     List<String> sourceDegradations) {
        Instant now = Instant.now();
        List<String> degradations = new ArrayList<>(sourceDegradations == null ? List.of() : sourceDegradations);
        List<MemoryManifestItem> manifest = new ArrayList<>();
        Map<String, Candidate> unique = new LinkedHashMap<>();

        for (MemorySegment raw : candidates == null ? List.<MemorySegment>of() : candidates) {
            MemorySegment segment = withEstimatedTokens(raw);
            MemoryPolicyEngine.PolicyDecision decision = policy.validateForPrompt(
                    query.scope(), query.phase(), segment);
            if (!decision.allowed()) {
                manifest.add(manifest(query, segment, false, decision.reason()));
                // 跨作用域不是普通“低质量候选”，而是严重安全错误；必须阻断并留下明确原因。
                if ("SCOPE_MISMATCH".equals(decision.reason()))
                    throw new SecurityException("Memory 候选不属于当前租户/用户作用域");
                continue;
            }
            if (!segment.promptEligibleAt(now)) {
                manifest.add(manifest(query, segment, false, "PROMPT_TTL_EXPIRED"));
                continue;
            }
            if (segment.estimatedTokens() > query.maxSingleMemoryTokens()) {
                manifest.add(manifest(query, segment, false, "SINGLE_MEMORY_TOKEN_LIMIT"));
                degradations.add("oversized_memory_dropped");
                continue;
            }

            // 同一层相同正文只保留效用最高的一条；例如 Redis 热窗口与 MySQL 回源不会重复占预算。
            String dedupKey = segment.layer() + "\u001f" + segment.content();
            Candidate current = new Candidate(segment, utility(segment, now));
            Candidate previous = unique.get(dedupKey);
            if (previous == null || current.utility() > previous.utility()) {
                if (previous != null) manifest.add(manifest(query, previous.segment(), false, "DEDUPLICATED"));
                unique.put(dedupKey, current);
            } else manifest.add(manifest(query, segment, false, "DEDUPLICATED"));
        }

        Map<BudgetBucket, Integer> limits = limits(query);
        Map<BudgetBucket, Integer> usedByBucket = new EnumMap<>(BudgetBucket.class);
        List<MemorySegment> selected = new ArrayList<>();
        int total = 0;
        List<Candidate> ordered = unique.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::utility).reversed()
                        .thenComparing(candidate -> candidate.segment().occurredAt(), Comparator.reverseOrder())
                        .thenComparing(candidate -> candidate.segment().memoryId()))
                .toList();
        for (Candidate candidate : ordered) {
            MemorySegment segment = candidate.segment();
            BudgetBucket bucket = bucket(segment);
            int bucketUsed = usedByBucket.getOrDefault(bucket, 0);
            if (bucketUsed + segment.estimatedTokens() > limits.getOrDefault(bucket, 0)) {
                manifest.add(manifest(query, segment, false, "LAYER_TOKEN_BUDGET_EXCEEDED"));
                continue;
            }
            if (total + segment.estimatedTokens() > query.maxTotalTokens()) {
                manifest.add(manifest(query, segment, false, "TOTAL_MEMORY_TOKEN_BUDGET_EXCEEDED"));
                continue;
            }
            selected.add(segment);
            total += segment.estimatedTokens();
            usedByBucket.put(bucket, bucketUsed + segment.estimatedTokens());
            manifest.add(manifest(query, segment, true, "SELECTED"));
        }

        /*
         * 效用排序只决定“选谁”，不决定最终阅读顺序。Prompt 固定为摘要 -> 最近消息 -> 情景 ->
         * 画像，同层按时间正序，模型才能正确理解对话先后；该顺序也与 ContextAssembler 的分区一致。
         */
        selected.sort(Comparator.comparingInt((MemorySegment value) -> renderOrder(value))
                .thenComparing(MemorySegment::occurredAt).thenComparing(MemorySegment::memoryId));
        boolean truncated = manifest.stream().anyMatch(item -> !item.selected());
        if (truncated) degradations.add("memory_budget_or_policy_trimmed");
        return new MemoryContextResult(List.copyOf(selected), List.copyOf(manifest), total, truncated,
                degradations.stream().distinct().toList());
    }

    private MemorySegment withEstimatedTokens(MemorySegment source) {
        int estimated = source.estimatedTokens() > 0 ? source.estimatedTokens() : tokens.estimate(source.content());
        return new MemorySegment(source.memoryId(), source.tenantId(), source.userId(), source.layer(),
                source.content(), source.sourceType(), source.trustLevel(), source.version(), source.relevance(),
                source.confidence(), source.occurredAt(), source.promptEligibleUntil(), estimated);
    }

    private Map<BudgetBucket, Integer> limits(MemoryContextQuery query) {
        Map<BudgetBucket, Integer> result = new EnumMap<>(BudgetBucket.class);
        result.put(BudgetBucket.RECENT, query.maxRecentMessageTokens());
        result.put(BudgetBucket.SUMMARY, query.maxSummaryTokens());
        result.put(BudgetBucket.EPISODIC, query.maxEpisodicTokens());
        result.put(BudgetBucket.PROFILE, query.maxProfileTokens());
        result.put(BudgetBucket.WORKING, query.maxTotalTokens());
        return result;
    }

    private BudgetBucket bucket(MemorySegment segment) {
        if (segment.layer() == MemoryLayer.CONVERSATION
                && ("CONVERSATION_SUMMARY".equals(segment.sourceType())
                || "CONVERSATION_TASK_INDEX".equals(segment.sourceType()))) return BudgetBucket.SUMMARY;
        return switch (segment.layer()) {
            case WORKING -> BudgetBucket.WORKING;
            case CONVERSATION -> BudgetBucket.RECENT;
            case EPISODIC -> BudgetBucket.EPISODIC;
            case PROFILE -> BudgetBucket.PROFILE;
        };
    }

    private double utility(MemorySegment segment, Instant now) {
        double priority = switch (bucket(segment)) {
            case WORKING -> 100d;
            case RECENT -> 85d;
            case SUMMARY -> 72d;
            case EPISODIC -> 45d;
            case PROFILE -> 38d;
        };
        long ageDays = Math.max(0, Duration.between(segment.occurredAt(), now).toDays());
        double freshness = segment.occurredAt().equals(Instant.EPOCH) ? 0.5 : Math.exp(-ageDays / 90d);
        double quality = Math.max(0.05, segment.relevance())
                * Math.max(0.05, segment.confidence())
                * Math.max(0.05, segment.trustLevel().score())
                * Math.max(0.10, freshness);
        return priority * quality / Math.max(1, segment.estimatedTokens());
    }

    private int renderOrder(MemorySegment segment) {
        return switch (bucket(segment)) {
            case WORKING -> 0;
            case SUMMARY -> 1;
            case RECENT -> 2;
            case EPISODIC -> 3;
            case PROFILE -> 4;
        };
    }

    private MemoryManifestItem manifest(MemoryContextQuery query, MemorySegment segment,
                                        boolean selected, String reason) {
        return new MemoryManifestItem(segment.memoryId(), segment.layer(), segment.sourceType(),
                segment.trustLevel(), segment.version(), segment.relevance(), segment.estimatedTokens(),
                query.scope().nodeName(), selected, reason);
    }

    private enum BudgetBucket { WORKING, RECENT, SUMMARY, EPISODIC, PROFILE }
    private record Candidate(MemorySegment segment, double utility) {}
}
