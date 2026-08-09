package com.hanaki.ecom.memory.api;

import com.hanaki.ecom.memory.domain.MemoryLoadPhase;
import com.hanaki.ecom.memory.domain.MemoryScope;

import java.util.Objects;

/**
 * 构建 Memory 上下文的应用层查询。Token 上限按层独立设置，避免某一层挤占全部预算。
 */
public record MemoryContextQuery(
        MemoryScope scope,
        MemoryLoadPhase phase,
        String currentMessage,
        int maxTotalTokens,
        int maxRecentMessageTokens,
        int maxSummaryTokens,
        int maxEpisodicTokens,
        int maxProfileTokens,
        int maxSingleMemoryTokens
) {
    public MemoryContextQuery {
        scope = Objects.requireNonNull(scope, "scope");
        phase = Objects.requireNonNullElse(phase, MemoryLoadPhase.MAIN);
        currentMessage = Objects.requireNonNullElse(currentMessage, "");
        maxTotalTokens = positive("maxTotalTokens", maxTotalTokens);
        maxRecentMessageTokens = nonNegative(maxRecentMessageTokens);
        maxSummaryTokens = nonNegative(maxSummaryTokens);
        maxEpisodicTokens = nonNegative(maxEpisodicTokens);
        maxProfileTokens = nonNegative(maxProfileTokens);
        maxSingleMemoryTokens = positive("maxSingleMemoryTokens", maxSingleMemoryTokens);
        if (phase == MemoryLoadPhase.MAIN && (maxEpisodicTokens > 0 || maxProfileTokens > 0)) {
            throw new IllegalArgumentException("主 Agent 阶段禁止分配情景记忆和画像 Token");
        }
    }

    private static int positive(String name, int value) {
        if (value <= 0) throw new IllegalArgumentException(name + " 必须大于 0");
        return value;
    }

    private static int nonNegative(int value) { return Math.max(0, value); }
}
