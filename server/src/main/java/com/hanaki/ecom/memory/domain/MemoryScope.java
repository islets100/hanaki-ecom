package com.hanaki.ecom.memory.domain;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 一次 Memory 访问的完整、不可变命名空间。
 *
 * <p>这里故意不把 tenantId、userId 等参数拆散传给各个 Repository。散乱参数很容易在新增
 * 查询条件时漏掉 tenantId，尤其是异步 Worker、定时清理和内部 RPC 并不一定存在 Web 安全
 * 上下文。所有 Memory 入口统一接收本对象后，读取层至少能够强制检查“租户 + 用户”，工作
 * 记忆还可以继续检查 conversation/run/subRun。</p>
 *
 * <p>tenantId 和 userId 必须来自服务端已经认证的上下文。这个类型只负责验证值是否完整、是否
 * 适合参与 Redis Key/审计字段，不能把来自 HTTP Body 或模型输出的字符串变成可信身份。</p>
 */
public record MemoryScope(
        String tenantId,
        String userId,
        String conversationId,
        String runId,
        String subRunId,
        String businessTaskId,
        String agentType,
        String nodeName,
        String channel
) {
    public MemoryScope {
        tenantId = requiredId("tenantId", tenantId);
        userId = requiredId("userId", userId);
        conversationId = requiredId("conversationId", conversationId);
        runId = optionalId("runId", runId);
        subRunId = optionalId("subRunId", subRunId);
        businessTaskId = optionalId("businessTaskId", businessTaskId);
        agentType = normalizedLabel(agentType, "MAIN");
        nodeName = normalizedLabel(nodeName, "UNKNOWN");
        channel = normalizedLabel(channel, "WEB");
    }

    /** 路由阶段没有业务 SubRun，只允许读取最小会话上下文。 */
    public static MemoryScope conversation(String tenantId, String userId, String conversationId,
                                           String runId, String subRunId, String nodeName) {
        return new MemoryScope(tenantId, userId, conversationId, runId, subRunId,
                "", "MAIN", nodeName, "WEB");
    }

    /**
     * 生成 Redis Key 的作用域前缀。三个强制身份字段都参与 Key，避免相同 conversationId 在不同
     * 租户或用户下碰撞。每段采用 URL-safe Base64，既支持历史系统中的中文/冒号 ID，也不会让
     * 用户控制的冒号伪造 Redis Key 层级；编码不是脱敏措施，访问控制仍由服务端身份负责。
     */
    public String conversationKey(String suffix) {
        String safeSuffix = Objects.requireNonNullElse(suffix, "").replaceAll("[^A-Za-z0-9._-]", "_");
        return "cs:" + safeSuffix + ":" + keyPart(tenantId) + ":" + keyPart(userId) + ":" + keyPart(conversationId);
    }

    /** 工作记忆或 Checkpoint 操作必须拥有完整 Run/SubRun，不能退化为用户级全局状态。 */
    public void requireRunNamespace() {
        if (runId.isBlank() || subRunId.isBlank()) {
            throw new IllegalArgumentException("工作记忆必须包含 runId 和 subRunId");
        }
    }

    private static String requiredId(String name, String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank() || normalized.length() > 160 || containsControl(normalized))
            throw new IllegalArgumentException(name + " 缺失、过长或包含控制字符");
        return normalized;
    }

    private static String optionalId(String name, String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.length() > 160 || containsControl(normalized))
            throw new IllegalArgumentException(name + " 过长或包含控制字符");
        return normalized;
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static String keyPart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizedLabel(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, fallback).strip().toUpperCase();
        return normalized.isBlank() ? fallback : normalized.substring(0, Math.min(64, normalized.length()));
    }
}
