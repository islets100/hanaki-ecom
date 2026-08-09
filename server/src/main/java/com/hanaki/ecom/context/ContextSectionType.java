package com.hanaki.ecom.context;

/**
 * 模型可见上下文的稳定分区。
 *
 * <p>分区的价值不只是为了排版：策略白名单、Token 配额、安全检查、审计清单和最终渲染顺序
 * 都以该枚举为共同协议。新增来源时必须先决定它属于哪个分区，禁止以“临时字符串”的方式绕过
 * ContextPolicy。这样才能回答“某个节点为什么看到了这段内容”，也能确保租户配置和外部资料
 * 永远不会被误放进平台安全指令之前。</p>
 */
public enum ContextSectionType {
    PLATFORM_SAFETY_RULE,
    AGENT_SYSTEM_PROMPT,
    TENANT_INSTRUCTION,
    NODE_INSTRUCTION,
    CURRENT_USER_MESSAGE,
    RECENT_MESSAGE,
    CONVERSATION_SUMMARY,
    EPISODIC_MEMORY,
    USER_PROFILE,
    BUSINESS_STATE,
    RAG_EVIDENCE,
    SKILL_CARD,
    SKILL_SCHEMA,
    TOOL_RESULT,
    MODEL_CANDIDATE,
    OUTPUT_CONSTRAINT
}
