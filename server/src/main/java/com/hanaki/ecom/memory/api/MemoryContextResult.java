package com.hanaki.ecom.memory.api;

import com.hanaki.ecom.memory.domain.MemorySegment;

import java.util.List;

/**
 * 统一 Memory 构建结果。segments 才能交给 Prompt 层；manifest 只用于 Trace/审计，不能注入模型。
 */
public record MemoryContextResult(
        List<MemorySegment> segments,
        List<MemoryManifestItem> manifest,
        int estimatedTokens,
        boolean truncated,
        List<String> degradationReasons
) {
    public MemoryContextResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
        manifest = manifest == null ? List.of() : List.copyOf(manifest);
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
    }

    /** 兼容现有 Graph State 的字符串列表；新代码可直接消费结构化 segments。 */
    public List<String> legacyLines() {
        return segments.stream().map(MemorySegment::content).toList();
    }
}
