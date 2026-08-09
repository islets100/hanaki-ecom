package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.TenantAgentConfigSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 读取并冻结一次 Agent Run 所使用的租户配置版本。
 *
 * <p>租户可以配置 Prompt、知识库、业务策略、允许启用的工具子集和客服队列，但不能修改平台级
 * 安全规则，也不能把未出现在服务端工具白名单中的名称变成可执行工具。本服务返回的是“版本快照”，
 * AgentOrchestrator 会在进入 Graph 前把它与 runId 一起固定；后续节点和恢复流程只读取这个快照，
 * 不会在同一次退款/补偿确认流程中重新加载最新版本而造成前后规则漂移。</p>
 *
 * <p>表中没有租户专属配置时使用平台发布版本。这里故意不根据用户自然语言选择版本，也不接受
 * API 请求体中的版本号：版本只能来自可信配置表或服务端部署配置。</p>
 */
@Service
public class TenantAgentConfigService {
    private static final List<String> PLATFORM_TOOL_CATALOG = List.of(
            "query_product", "query_stock", "recommend_product",
            "query_order", "query_logistics", "urge_delivery",
            "query_policy", "preview_refund", "submit_refund",
            "create_ticket", "handoff_human");

    private final JdbcClient db;
    private final TenantAgentConfigSnapshot platformDefaults;

    public TenantAgentConfigService(
            JdbcClient db,
            @Value("${agent.checkpoint.graph-version:customer-service-graph-v3}") String graphVersion,
            @Value("${agent.observability.prompt-version:local}") String promptVersion,
            @Value("${agent.config.policy-version:policy-v1}") String policyVersion,
            @Value("${agent.config.knowledge-base-version:knowledge-v1}") String knowledgeVersion,
            @Value("${agent.config.tool-schema-version:tool-schema-v3}") String toolSchemaVersion,
            @Value("${agent.config.routing-version:routing-v1}") String routingVersion,
            @Value("${agent.config.topology-version:standard-v1}") String topologyVersion) {
        this.db = db;
        this.platformDefaults = new TenantAgentConfigSnapshot(graphVersion, promptVersion, policyVersion,
                knowledgeVersion, toolSchemaVersion, routingVersion, topologyVersion,
                PLATFORM_TOOL_CATALOG, "DEFAULT");
    }

    /**
     * 返回当前已发布配置的不可变副本。enabled_tools 使用逗号分隔只是演示数据库的轻量实现；
     * 真正授权时 ToolGateway 仍会与平台白名单求交集，租户配置永远只能收窄权限。
     */
    public TenantAgentConfigSnapshot snapshot(String tenantId) {
        if (tenantId == null || tenantId.isBlank())
            throw new IllegalArgumentException("加载 Agent 配置时缺少可信 tenantId");
        return db.sql("select config_version,prompt_version,policy_version,knowledge_base_version," +
                        "tool_schema_version,routing_config_version,topology_version,enabled_tools," +
                        "customer_service_queue from tenant_agent_config where tenant_id=:tenant and active=true " +
                        "order by published_at desc limit 1")
                .param("tenant", tenantId.strip())
                .query((rs, row) -> new TenantAgentConfigSnapshot(
                        rs.getString("config_version"), rs.getString("prompt_version"),
                        rs.getString("policy_version"), rs.getString("knowledge_base_version"),
                        rs.getString("tool_schema_version"), rs.getString("routing_config_version"),
                        rs.getString("topology_version"), parseTools(rs.getString("enabled_tools")),
                        rs.getString("customer_service_queue")))
                .optional().orElse(platformDefaults);
    }

    private List<String> parseTools(String raw) {
        if (raw == null || raw.isBlank()) return platformDefaults.enabledTools();
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                // 配置发布错误时也不能把任意字符串变成工具。未知名称在进入 Graph 前即被丢弃。
                .filter(PLATFORM_TOOL_CATALOG::contains)
                .distinct()
                .toList();
    }
}
