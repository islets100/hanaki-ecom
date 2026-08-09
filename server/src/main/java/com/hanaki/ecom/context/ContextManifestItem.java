package com.hanaki.ecom.context;

/** 审计清单中的单项快照；不保存原始 content，避免审计系统成为新的敏感数据副本。 */
public record ContextManifestItem(
        String itemId,
        ContextSectionType sectionType,
        String sourceId,
        String sourceType,
        String version,
        String contentHash,
        int tokenCount,
        int priority,
        TrustLevel trustLevel,
        SensitivityLevel sensitivityLevel,
        String loadReason,
        boolean trimmed,
        String trimReason,
        ContextDelivery delivery
) {}
