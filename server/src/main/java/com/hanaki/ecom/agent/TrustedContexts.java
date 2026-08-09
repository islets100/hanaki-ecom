package com.hanaki.ecom.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.hanaki.ecom.context.TrustedRequestContext;
import com.hanaki.ecom.domain.Domain.RiskLevel;

/**
 * 从服务端放入 Graph State 的不可变对象取得模型调用身份。
 *
 * <p>这些字段最初由 EcommerceApi 使用登录会话覆盖客户端输入，再由 AgentOrchestrator 创建
 * {@link TrustedRequestContext}。Graph State 里的 tenantId/userId 等字符串只是方便检索和审计的
 * 副本；本工厂先取得不可变对象，再逐项核对这些副本。它不会从 content、entities、摘要或模型
 * 输出中猜测身份。工具网关执行时仍会再次检查资源归属，形成“组装前 + 执行前”两道边界。</p>
 */
final class TrustedContexts {
    private TrustedContexts() {}

    static TrustedRequestContext from(OverAllState state) {
        Object raw = state.value("trustedContext").orElseThrow(() ->
                new IllegalStateException("Graph State 缺少不可变 trustedContext"));
        if (!(raw instanceof TrustedRequestContext trusted))
            throw new IllegalStateException("Graph State 的 trustedContext 类型无效");
        trusted.assertIdentityCopies(required(state, "tenantId"), required(state, "userId"),
                required(state, "conversationId"), required(state, "runId"),
                required(state, "traceId"), required(state, "threadId"));
        return trusted.withRiskLevel(risk(state.value("riskLevel", "LOW")));
    }

    private static String required(OverAllState state, String key) {
        String value = state.value(key, "");
        if (value.isBlank()) throw new IllegalStateException("Graph State 缺少可信字段：" + key);
        return value;
    }

    private static RiskLevel risk(String value) {
        try { return RiskLevel.valueOf(value); }
        catch (Exception ignored) { return RiskLevel.LOW; }
    }
}
