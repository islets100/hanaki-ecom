package com.hanaki.ecom.memory.api;

import com.hanaki.ecom.memory.domain.MemoryLayer;
import com.hanaki.ecom.memory.domain.MemoryTrustLevel;

/** 每个 Memory 单元是否进入 Prompt 的审计结果；正文不写入审计，避免复制敏感信息。 */
public record MemoryManifestItem(
        String memoryId,
        MemoryLayer layer,
        String sourceType,
        MemoryTrustLevel trustLevel,
        String version,
        double retrievalScore,
        int tokenCount,
        String loadedByNode,
        boolean selected,
        String decisionReason
) {}
