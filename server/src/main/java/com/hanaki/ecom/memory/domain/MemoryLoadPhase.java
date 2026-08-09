package com.hanaki.ecom.memory.domain;

/**
 * Memory 的两阶段披露策略。
 * MAIN 只服务意图路由；BUSINESS 必须在路由完成后携带具体 agentType 才能读取情景和画像。
 */
public enum MemoryLoadPhase {
    MAIN,
    BUSINESS
}
