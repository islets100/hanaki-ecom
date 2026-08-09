package com.hanaki.ecom.memory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 可持久化的“小工作记忆”契约。
 *
 * <p>它只包含恢复执行所需的确认实体、风险、任务引用和待确认动作；完整订单、物流轨迹、RAG
 * 正文、用户画像、模型完整响应、推理过程和凭证都不属于该契约。Checkpoint 恢复后仍要使用
 * businessTaskId/实体重新查询真实业务系统，并比较业务版本。</p>
 */
public record CustomerServiceRunState(
        String tenantId,
        String userId,
        String runId,
        String conversationId,
        String threadId,
        String currentNode,
        String intent,
        double routeConfidence,
        String businessTaskId,
        String tenantConfigVersion,
        String promptVersion,
        String policyVersion,
        String knowledgeBaseVersion,
        String toolSchemaVersion,
        String routingConfigVersion,
        String topologyVersion,
        Map<String, String> confirmedEntities,
        List<String> riskTags,
        List<ToolResultReference> toolResultRefs,
        PendingAction pendingAction,
        long stateVersion,
        Instant updatedAt
) {
    public CustomerServiceRunState {
        confirmedEntities = confirmedEntities == null ? Map.of() : Map.copyOf(confirmedEntities);
        riskTags = riskTags == null ? List.of() : List.copyOf(riskTags);
        toolResultRefs = toolResultRefs == null ? List.of() : List.copyOf(toolResultRefs);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public record ToolResultReference(String resultId, String resultType, String status) {}
    public record PendingAction(String actionType, String businessTaskId, Instant expiresAt) {}
}
