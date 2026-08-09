package com.hanaki.ecom.context;

/**
 * 上下文来源的信任等级。枚举顺序不表达大小关系，安全判断必须显式列出允许值。
 */
public enum TrustLevel {
    /** 平台发布且不可被租户覆盖的安全策略、节点协议和 Skill 定义。 */
    TRUSTED_PLATFORM,
    /** 订单、状态机、规则引擎和统一工具网关返回的实时事实。 */
    TRUSTED_BUSINESS,
    /** 通过租户范围、版本和有效期校验的已发布知识。 */
    VERIFIED_KNOWLEDGE,
    /** 由系统生成、脱敏并带版本保存的摘要，仍不能覆盖实时业务事实。 */
    DERIVED_SUMMARY,
    /** 用户通过画像审批或设置页明确确认的稳定偏好；仍不能影响退款、权限等业务决策。 */
    USER_CONFIRMED,
    /** RAG、上传文件或第三方系统中的文本，只能作为数据使用。 */
    UNTRUSTED_EXTERNAL,
    /** 当前用户输入以及原始历史消息，只能作为数据使用。 */
    UNTRUSTED_USER
}
