package com.hanaki.ecom.memory.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * MemoryContextBuilder 的统一输入单元。存储适配器只负责把数据库/Redis/ES 结果映射为该类型，
 * 不允许自行拼接整份 Prompt。tenantId/userId 被保留在每个单元上，Builder 会再次做作用域校验。
 */
public record MemorySegment(
        String memoryId,
        String tenantId,
        String userId,
        MemoryLayer layer,
        String content,
        String sourceType,
        MemoryTrustLevel trustLevel,
        String version,
        double relevance,
        double confidence,
        Instant occurredAt,
        Instant promptEligibleUntil,
        int estimatedTokens
) {
    public MemorySegment {
        memoryId = Objects.requireNonNullElse(memoryId, "").strip();
        tenantId = Objects.requireNonNullElse(tenantId, "").strip();
        userId = Objects.requireNonNullElse(userId, "").strip();
        layer = Objects.requireNonNull(layer, "layer");
        content = Objects.requireNonNullElse(content, "").strip();
        sourceType = Objects.requireNonNullElse(sourceType, "UNKNOWN").strip();
        trustLevel = Objects.requireNonNullElse(trustLevel, MemoryTrustLevel.EXTERNAL_UNVERIFIED);
        version = Objects.requireNonNullElse(version, "").strip();
        relevance = clamp(relevance);
        confidence = clamp(confidence);
        occurredAt = occurredAt == null ? Instant.EPOCH : occurredAt;
        estimatedTokens = Math.max(0, estimatedTokens);
    }

    public boolean promptEligibleAt(Instant now) {
        return promptEligibleUntil == null || promptEligibleUntil.isAfter(now);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) return 0d;
        return Math.max(0d, Math.min(1d, value));
    }
}
