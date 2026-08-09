package com.hanaki.ecom.memory.application;

import com.hanaki.ecom.memory.domain.MemoryLayer;
import com.hanaki.ecom.memory.domain.MemoryLoadPhase;
import com.hanaki.ecom.memory.domain.MemoryScope;
import com.hanaki.ecom.memory.domain.MemorySegment;
import com.hanaki.ecom.memory.domain.MemoryTrustLevel;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Memory 读取和写入共用的确定性策略引擎。
 *
 * <p>模型只能生成候选，不能扩展画像字段目录、改变可信度或决定是否进入 Prompt。把这些规则放在
 * Java 白名单中后，模型升级、Prompt 变化或恶意历史消息都不能获得新的数据权限。</p>
 */
@Service
public final class MemoryPolicyEngine {
    /**
     * 字段目录同时描述默认 TTL 和允许读取的 Agent。这里保留现有项目的中文 factKey，避免数据库
     * 迁移期间出现两套语义；对外仍应把它们视为稳定 attributeCode，而不是任意显示名称。
     */
    private static final Map<String, ProfileDefinition> PROFILE_CATALOG = Map.of(
            "尺码偏好", new ProfileDefinition(90, Set.of("PRE_SALE")),
            "颜色偏好", new ProfileDefinition(180, Set.of("PRE_SALE")),
            "品牌偏好", new ProfileDefinition(180, Set.of("PRE_SALE")),
            "收货时段", new ProfileDefinition(90, Set.of("PRE_SALE", "IN_SALE")),
            "沟通偏好", new ProfileDefinition(365, Set.of("PRE_SALE", "IN_SALE", "AFTER_SALE", "COMPLAINT")),
            "语言偏好", new ProfileDefinition(365, Set.of("PRE_SALE", "IN_SALE", "AFTER_SALE", "COMPLAINT"))
    );

    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)(忽略.{0,12}(规则|指令|system)|system\\s*prompt|开发者消息|无需确认.{0,12}(退款|补偿)|" +
                    "直接给我.{0,8}(退款|权限)|记住我是.{0,8}(管理员|客服)|上一位用户|其他用户.{0,8}(订单|信息))");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(?:\\b(?:OD|BT|TK)[A-Za-z0-9-]+\\b|(?<!\\d)1[3-9]\\d{9}(?!\\d)|身份证|银行卡|" +
                    "密码|api.?key|完整地址|退款金额|账户余额|健康信息)");

    /** 主 Agent 只能拿到会话层；业务层的字段权限还会继续按 Agent 投影。 */
    public boolean mayReadLayer(MemoryLoadPhase phase, MemoryLayer layer) {
        if (phase == MemoryLoadPhase.MAIN) return layer == MemoryLayer.CONVERSATION;
        return layer != MemoryLayer.WORKING; // 工作记忆由 Graph State 直接提供，不从持久 Memory 回读。
    }

    /** 返回当前 Agent 允许读取的画像属性，SQL 必须使用此集合做白名单查询。 */
    public Set<String> allowedProfileAttributes(String agentType) {
        String normalized = normalizeAgent(agentType);
        return PROFILE_CATALOG.entrySet().stream()
                .filter(entry -> entry.getValue().allowedAgentTypes().contains(normalized))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean registeredProfileAttribute(String attributeCode) {
        return PROFILE_CATALOG.containsKey(attributeCode);
    }

    /** 用户画像管理页可以查看自己的全部已确认字段；该集合不能直接用于 Agent Prompt 投影。 */
    public Set<String> allProfileAttributes() { return PROFILE_CATALOG.keySet(); }

    public int clampProfileTtl(String attributeCode, int requestedDays) {
        ProfileDefinition definition = PROFILE_CATALOG.get(attributeCode);
        if (definition == null) throw new IllegalArgumentException("未注册的画像属性：" + attributeCode);
        int requested = requestedDays <= 0 ? definition.defaultTtlDays() : requestedDays;
        // 即使模型返回极端 TTL，也不能突破字段目录的上限；最短七天避免立即过期造成反复提取。
        return Math.max(7, Math.min(definition.defaultTtlDays(), requested));
    }

    /**
     * 写入候选前的硬校验。显式确认和高置信度也不会绕过本方法，更不会自动变成 CONFIRMED。
     */
    public PolicyDecision validateProfileCandidate(String factKey, String factValue,
                                                   String memoryType, double confidence) {
        if (!registeredProfileAttribute(factKey)) return PolicyDecision.reject("ATTRIBUTE_NOT_REGISTERED");
        if (!"PREFERENCE".equalsIgnoreCase(memoryType)) return PolicyDecision.reject("MEMORY_TYPE_NOT_ALLOWED");
        if (factValue == null || factValue.isBlank() || factValue.length() > 80)
            return PolicyDecision.reject("INVALID_VALUE_LENGTH");
        if (!Double.isFinite(confidence) || confidence < 0.65)
            return PolicyDecision.reject("LOW_CONFIDENCE");
        if (containsSensitive(factValue)) return PolicyDecision.reject("SENSITIVE_VALUE");
        if (containsPromptInjection(factValue)) return PolicyDecision.reject("PROMPT_INJECTION");
        return PolicyDecision.allow();
    }

    /**
     * 读取侧再次检查。数据库中可能存在旧版本脏数据，不能假设“写入时验证过”就永久安全。
     */
    public PolicyDecision validateForPrompt(MemoryScope scope, MemoryLoadPhase phase, MemorySegment segment) {
        if (!scope.tenantId().equals(segment.tenantId()) || !scope.userId().equals(segment.userId()))
            return PolicyDecision.reject("SCOPE_MISMATCH");
        if (!mayReadLayer(phase, segment.layer())) return PolicyDecision.reject("LAYER_NOT_ALLOWED_IN_PHASE");
        if (segment.content().isBlank()) return PolicyDecision.reject("EMPTY_CONTENT");
        if (containsPromptInjection(segment.content())) return PolicyDecision.reject("PROMPT_INJECTION");
        if (segment.layer() == MemoryLayer.PROFILE) {
            String attributeCode = segment.memoryId().startsWith("profile:")
                    ? segment.memoryId().substring("profile:".length()) : "";
            if (!allowedProfileAttributes(scope.agentType()).contains(attributeCode))
                return PolicyDecision.reject("PROFILE_NOT_ALLOWED_FOR_AGENT");
            if (segment.trustLevel() != MemoryTrustLevel.USER_CONFIRMED
                    && segment.trustLevel() != MemoryTrustLevel.HUMAN_VERIFIED
                    && segment.trustLevel() != MemoryTrustLevel.BUSINESS_VERIFIED)
                return PolicyDecision.reject("PROFILE_NOT_CONFIRMED");
        }
        return PolicyDecision.allow();
    }

    public boolean containsPromptInjection(String value) {
        return value != null && PROMPT_INJECTION.matcher(value).find();
    }

    public boolean containsSensitive(String value) {
        return value != null && SENSITIVE.matcher(value).find();
    }

    private String normalizeAgent(String value) {
        return value == null ? "MAIN" : value.strip().toUpperCase(Locale.ROOT);
    }

    public record ProfileDefinition(int defaultTtlDays, Set<String> allowedAgentTypes) {}

    public record PolicyDecision(boolean allowed, String reason) {
        static PolicyDecision allow() { return new PolicyDecision(true, "ALLOWED"); }
        static PolicyDecision reject(String reason) { return new PolicyDecision(false, reason); }
    }
}
