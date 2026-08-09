package com.hanaki.ecom.memory.infrastructure.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.hanaki.ecom.agent.CacheKeyBuilder;
import com.hanaki.ecom.memory.domain.CustomerServiceRunState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 把具体 Graph State 投影为框架无关的小工作记忆。Graph API 的字段读取集中在本适配器中，未来
 * 升级 Spring AI Alibaba Graph 时不会迫使 Memory 领域对象跟着改变。
 */
@Component
public final class GraphWorkingMemoryAdapter {
    public CustomerServiceRunState project(OverAllState state, String currentNode) {
        String businessTaskId = state.value("businessTaskId", "");
        String status = state.value("status", "COMPLETED");
        CustomerServiceRunState.PendingAction pending = switch (status) {
            case "WAITING_CONFIRMATION", "WAITING_STAFF_APPROVAL" ->
                    new CustomerServiceRunState.PendingAction(status, businessTaskId,
                            Instant.now().plus(7, ChronoUnit.DAYS));
            default -> null;
        };
        List<CustomerServiceRunState.ToolResultReference> references = strings(state, "toolResults").stream()
                .map(value -> new CustomerServiceRunState.ToolResultReference(
                        "tool-result-" + CacheKeyBuilder.digest(value).substring(0, 16), "TOOL_RESULT", "RECORDED"))
                .toList();
        return new CustomerServiceRunState(state.value("tenantId", ""), state.value("userId", ""),
                state.value("runId", ""), state.value("conversationId", ""), state.value("threadId", ""),
                currentNode, state.value("intent", "UNKNOWN"),
                ((Number) state.value("confidence", 0d)).doubleValue(), businessTaskId,
                state.value("tenantConfigVersion", ""), state.value("promptVersion", ""),
                state.value("policyVersion", ""), state.value("knowledgeBaseVersion", ""),
                state.value("toolSchemaVersion", ""), state.value("routingConfigVersion", ""),
                state.value("topologyVersion", ""),
                stringMap(state, "entities"), strings(state, "riskFlags"), references,
                pending, 1L, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(OverAllState state, String key) {
        Object value = state.value(key).orElse(Map.of());
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        return raw.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> String.valueOf(entry.getKey()), entry -> String.valueOf(entry.getValue())));
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(OverAllState state, String key) {
        Object value = state.value(key).orElse(List.of());
        if (!(value instanceof List<?> raw)) return List.of();
        return raw.stream().map(String::valueOf).toList();
    }
}
