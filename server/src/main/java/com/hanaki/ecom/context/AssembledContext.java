package com.hanaki.ecom.context;

import java.util.List;

/** 最终交给模型网关的两条消息、授权工具集合与可回放清单。 */
public record AssembledContext(
        String systemPrompt,
        String userPrompt,
        List<String> boundSkillKeys,
        ContextManifest manifest
) {
    public AssembledContext {
        boundSkillKeys = List.copyOf(boundSkillKeys);
    }
}
