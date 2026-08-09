package com.hanaki.ecom.context;

import com.hanaki.ecom.domain.Domain.RiskLevel;
import com.hanaki.ecom.domain.Domain.TenantAgentConfigSnapshot;

import java.util.Set;

/**
 * 由服务端认证链路创建的可信身份与运行标识。
 *
 * <p>tenantId/userId 只能来自登录会话或网关鉴权。Graph State 中即使保存同名审计副本，组装时
 * 仍必须与本对象逐项比对；用户文本、历史摘要和模型输出都不能创建或覆盖此对象。把整个对象作为
 * 一个不可变值显式传入 Graph，也避免仅依赖 ThreadLocal：Graph 节点切换到异步线程后，仍能从
 * State 取得同一个可信身份。MQ 消费者则必须在验签消息头后重新创建该对象。</p>
 *
 * <p>{@code threadId} 专供 Spring AI Alibaba Graph Checkpointer 标识可恢复执行历史；
 * {@code conversationId} 表示用户会话，二者不能混用。{@code configSnapshot} 固定本 Run 使用的
 * Prompt、规则、知识库、工具 Schema 和拓扑版本。businessTaskId 在纯咨询阶段可以为空。</p>
 */
public record TrustedRequestContext(
        String tenantId,
        String userId,
        String conversationId,
        String threadId,
        String runId,
        String traceId,
        String businessTaskId,
        Set<String> permissions,
        String authenticationSource,
        TenantAgentConfigSnapshot configSnapshot,
        RiskLevel riskLevel
) {
    public TrustedRequestContext {
        tenantId = required(tenantId, "tenantId");
        userId = required(userId, "userId");
        conversationId = required(conversationId, "conversationId");
        threadId = required(threadId, "threadId");
        runId = required(runId, "runId");
        traceId = required(traceId, "traceId");
        businessTaskId = businessTaskId == null ? "" : businessTaskId.strip();
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        authenticationSource = required(authenticationSource, "authenticationSource");
        if (configSnapshot == null) throw new IllegalArgumentException("可信请求上下文缺少 configSnapshot");
        riskLevel = riskLevel == null ? RiskLevel.LOW : riskLevel;
    }

    /**
     * 兼容独立单元测试和旧的候选/Judge 构造代码。正式 Web Graph 会使用包含真实 threadId、权限、
     * 认证来源和租户配置快照的完整构造器。
     */
    public TrustedRequestContext(String tenantId, String userId, String conversationId,
                                 String runId, String traceId, String businessTaskId,
                                 RiskLevel riskLevel) {
        this(tenantId, userId, conversationId, "thread-" + conversationId, runId, traceId,
                businessTaskId, Set.of(), "INTERNAL_COMPAT",
                new TenantAgentConfigSnapshot("local", "local", "local", "local",
                        "local", "local", "standard-v1", java.util.List.of(), "DEFAULT"),
                riskLevel);
    }

    /** 风险等级是语义风控产生的运行信号；更新它不会改变经过认证的身份或版本快照。 */
    public TrustedRequestContext withRiskLevel(RiskLevel risk) {
        return new TrustedRequestContext(tenantId, userId, conversationId, threadId, runId, traceId,
                businessTaskId, permissions, authenticationSource, configSnapshot, risk);
    }

    /**
     * 每个安全边界都调用此方法核对 State 中的便捷字段。若未来某个节点误写 tenantId/userId，
     * 这里会立即失败关闭，而不是拿被污染的字段继续查询订单。
     */
    public void assertIdentityCopies(String stateTenantId, String stateUserId,
                                     String stateConversationId, String stateRunId,
                                     String stateTraceId, String stateThreadId) {
        if (!tenantId.equals(stateTenantId) || !userId.equals(stateUserId)
                || !conversationId.equals(stateConversationId) || !runId.equals(stateRunId)
                || !traceId.equals(stateTraceId) || !threadId.equals(stateThreadId)) {
            throw new SecurityException("Graph State 的身份副本与可信执行上下文不一致");
        }
    }

    /** 仅供旧接口兼容和纯单元测试使用；正式 Graph 调用不会使用该身份。 */
    public static TrustedRequestContext localTest() {
        return new TrustedRequestContext("local-test", "local-test", "local-test",
                "local-test", "local-test", "", RiskLevel.LOW);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("可信请求上下文缺少 " + field);
        return value.strip();
    }
}
