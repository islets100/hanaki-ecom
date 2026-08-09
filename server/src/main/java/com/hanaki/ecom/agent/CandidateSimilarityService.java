package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentDraft;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 确定性字符二元组（bigram）Jaccard 相似度。
 *
 * <p>Best-of-3 的价值来自不同推理与表达策略；如果三个答案只是换标点或调整语序，继续调用 Judge
 * 既浪费 Token，也会制造虚假的“多数意见”。本服务去掉标点和空白后比较字符二元组集合，结果
 * 范围固定为 0..1，不依赖外部 Embedding 模型，因而可重复、低成本且适合用作硬降级条件。</p>
 */
@Service
public final class CandidateSimilarityService {
    public double maximumSimilarity(List<AgentDraft> candidates) {
        // 三个候选只需比较 3 对；返回最大值意味着任意一对过度相似都会触发低多样性保护。
        double maximum = 0d;
        for (int left = 0; left < candidates.size(); left++) {
            for (int right = left + 1; right < candidates.size(); right++) {
                maximum = Math.max(maximum, similarity(candidates.get(left).answer(), candidates.get(right).answer()));
            }
        }
        return maximum;
    }

    double similarity(String left, String right) {
        Set<String> a = bigrams(normalize(left));
        Set<String> b = bigrams(normalize(right));
        if (a.isEmpty() && b.isEmpty()) return 1d;
        if (a.isEmpty() || b.isEmpty()) return 0d;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size(); // J(A,B)=|交集|/|并集|
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new HashSet<>();
        if (value.length() == 1) result.add(value);
        for (int index = 0; index + 1 < value.length(); index++) result.add(value.substring(index, index + 2));
        return result;
    }
}
