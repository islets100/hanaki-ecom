package com.hanaki.ecom.memory.domain;

/**
 * Memory 专用可信度。不要依赖枚举 ordinal 比较可信度，调用方应使用显式 score 或白名单。
 */
public enum MemoryTrustLevel {
    SYSTEM_POLICY(1.00, true),
    BUSINESS_VERIFIED(1.00, true),
    HUMAN_VERIFIED(0.95, true),
    USER_CONFIRMED(0.90, false),
    USER_CLAIMED(0.60, false),
    MODEL_EXTRACTED(0.35, false),
    EXTERNAL_UNVERIFIED(0.20, false);

    private final double score;
    private final boolean mayAffectBusinessDecision;

    MemoryTrustLevel(double score, boolean mayAffectBusinessDecision) {
        this.score = score;
        this.mayAffectBusinessDecision = mayAffectBusinessDecision;
    }

    public double score() { return score; }
    public boolean mayAffectBusinessDecision() { return mayAffectBusinessDecision; }
}
