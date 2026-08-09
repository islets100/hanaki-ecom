package com.hanaki.ecom.context;

import java.time.Instant;
import java.util.Map;

/**
 * 进入预算器之前的最小上下文原子。
 *
 * <p>content 仅在本次组装的内存中存在；审计表只保存 contentHash、来源、版本和 Token 数，避免
 * 把完整用户消息、业务对象或政策文本复制到审计库。preserveWhole=true 的项目（安全规则、业务
 * 事实和完整工具 Schema）禁止字符串截断：空间不足时要么删除其他可选项，要么 fail-closed。</p>
 */
public record ContextItem(
        String itemId,
        ContextSectionType sectionType,
        String content,
        String sourceType,
        String sourceId,
        String tenantId,
        TrustLevel trustLevel,
        SensitivityLevel sensitivityLevel,
        int priority,
        int estimatedTokens,
        boolean required,
        boolean preserveWhole,
        ContextDelivery delivery,
        Instant fetchedAt,
        Instant expiresAt,
        String version,
        String contentHash,
        String loadReason,
        Map<String, Object> metadata
) {
    public ContextItem {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        content = content == null ? "" : content;
        tenantId = tenantId == null ? "" : tenantId;
        estimatedTokens = Math.max(0, estimatedTokens);
    }

    public ContextItem withContent(String guardedContent, int guardedTokens, String guardedHash,
                                   Map<String, Object> guardedMetadata) {
        return new ContextItem(itemId, sectionType, guardedContent, sourceType, sourceId, tenantId,
                trustLevel, sensitivityLevel, priority, guardedTokens, required, preserveWhole, delivery,
                fetchedAt, expiresAt, version, guardedHash, loadReason, guardedMetadata);
    }
}
