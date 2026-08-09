package com.hanaki.ecom.context;

/** Skill 的渐进式披露阶段。NONE 和 CARD 阶段绝不允许绑定真实工具 Schema。 */
public enum SkillDisclosurePhase {
    NONE,
    CARD,
    SCHEMA
}
