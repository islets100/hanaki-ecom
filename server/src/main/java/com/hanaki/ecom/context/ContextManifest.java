package com.hanaki.ecom.context;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 一次模型调用实际使用的上下文清单。
 *
 * <p>Manifest 与 Graph Checkpoint 的职责不同：Checkpoint 用于恢复业务编排，Manifest 用于回答
 * “本次模型究竟看到了哪些版本、为什么加载、什么被裁剪”。恢复任务时只复用版本引用和哈希，
 * 不复用旧的完整 Prompt；业务状态、权限、风险和 Skill 授权必须重新查询。</p>
 */
public record ContextManifest(
        String manifestId,
        String tenantId,
        String conversationId,
        String runId,
        String traceId,
        String businessTaskId,
        String agentType,
        ContextNode nodeCode,
        String modelName,
        String policyVersion,
        Map<String, String> promptVersions,
        List<ContextManifestItem> items,
        int totalInputTokens,
        int reservedOutputTokens,
        int safetyReserveTokens,
        int toolProtocolTokens,
        List<String> trimmedItemIds,
        List<String> trimReasons,
        List<String> securityEvents,
        String assembledContextHash,
        Instant assembledAt,
        long assembleCostMillis
) {
    public ContextManifest {
        promptVersions = Map.copyOf(promptVersions);
        items = List.copyOf(items);
        trimmedItemIds = List.copyOf(trimmedItemIds);
        trimReasons = List.copyOf(trimReasons);
        securityEvents = List.copyOf(securityEvents);
    }
}
