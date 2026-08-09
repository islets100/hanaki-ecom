package com.hanaki.ecom.agent;

/**
 * 由服务端可信身份与 Graph 运行状态构造的缓存上下文。
 *
 * <p>它故意不接受模型生成的 tenantId/userId。模型只能给出业务查询参数，隔离字段必须来自
 * 已认证请求。traceId 仅用于观测，{@link CacheKeyBuilder} 明确不会把它放入 Key，否则同一资源
 * 每次请求都会产生不同键而失去复用价值。</p>
 */
public record CacheContext(
        String tenantId,
        String userId,
        String conversationId,
        String runId,
        String agentId,
        String nodeId,
        String permissionIdentity,
        String knowledgeVersion,
        String promptVersion,
        String modelVersion,
        String toolSchemaVersion,
        long mutationEpoch,
        String traceId) {

    public CacheContext {
        tenantId = clean(tenantId);
        userId = clean(userId);
        conversationId = clean(conversationId);
        runId = clean(runId);
        agentId = clean(agentId);
        nodeId = clean(nodeId);
        permissionIdentity = clean(permissionIdentity);
        knowledgeVersion = clean(knowledgeVersion);
        promptVersion = clean(promptVersion);
        modelVersion = clean(modelVersion);
        toolSchemaVersion = clean(toolSchemaVersion);
        traceId = clean(traceId);
        if (tenantId.isBlank()) throw new IllegalArgumentException("缓存上下文必须包含可信 tenantId");
        if (mutationEpoch < 0) throw new IllegalArgumentException("mutationEpoch 不能为负数");
    }

    public static CacheContext tenant(String tenantId, String permissionIdentity,
                                      String knowledgeVersion, String modelVersion, String traceId) {
        return new CacheContext(tenantId, "", "", "", "rag", "retrieval",
                permissionIdentity, knowledgeVersion, "", modelVersion, "", 0L, traceId);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
