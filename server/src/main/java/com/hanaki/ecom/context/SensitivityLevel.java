package com.hanaki.ecom.context;

/** 模型可见数据的敏感等级；ContextGuard 会依据节点职责执行不同强度的脱敏。 */
public enum SensitivityLevel {
    PUBLIC,
    INTERNAL,
    PERSONAL,
    RESTRICTED
}
