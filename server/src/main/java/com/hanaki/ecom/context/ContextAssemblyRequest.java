package com.hanaki.ecom.context;

import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;

import java.util.List;
import java.util.Map;

/**
 * 模型节点提交给 ContextAssembler 的结构化输入。
 *
 * <p>业务调用方只能提交数据，不直接拼接 Prompt。nodeCode 决定 ContextPolicy；trustedContext
 * 决定租户和用户作用域；selectedSkillKey 只是上一个确定性节点选出的候选，仍要经过策略与
 * SkillRegistry 二次授权后才能得到 boundSkillKeys。</p>
 */
public record ContextAssemblyRequest(
        TrustedRequestContext trustedContext,
        Intent intent,
        ContextNode nodeCode,
        int candidateVariant,
        String currentMessage,
        String rewrittenQuery,
        List<String> recentMessages,
        List<KnowledgeDoc> evidence,
        Map<String, Object> businessFacts,
        List<AgentDraft> candidates,
        String selectedSkillKey,
        SkillDisclosurePhase skillDisclosurePhase,
        String modelName,
        int requestedContextWindow
) {
    public ContextAssemblyRequest {
        if (trustedContext == null) throw new IllegalArgumentException("缺少可信请求上下文");
        intent = intent == null ? Intent.UNKNOWN : intent;
        if (nodeCode == null) throw new IllegalArgumentException("缺少模型节点代码");
        currentMessage = currentMessage == null ? "" : currentMessage;
        rewrittenQuery = rewrittenQuery == null ? "" : rewrittenQuery;
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        businessFacts = businessFacts == null ? Map.of() : Map.copyOf(businessFacts);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        selectedSkillKey = selectedSkillKey == null ? "" : selectedSkillKey.strip();
        skillDisclosurePhase = skillDisclosurePhase == null ? SkillDisclosurePhase.NONE : skillDisclosurePhase;
        modelName = modelName == null || modelName.isBlank() ? "unknown" : modelName.strip();
    }
}
