package com.hanaki.ecom.agent;

import com.hanaki.ecom.context.ContextAssemblyRequest;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.domain.Domain.ModelAnswer;
import com.hanaki.ecom.domain.Domain.ModelJudge;
import com.hanaki.ecom.domain.Domain.ModelRoute;
import com.hanaki.ecom.domain.Domain.RefundReasonAssessmentRequest;
import com.hanaki.ecom.domain.Domain.RefundReasonScore;

import java.util.List;
import java.util.Map;

/**
 * 所有大模型调用的单一边界。业务代码依赖这个接口，而不是依赖某个模型厂商 SDK；
 * 当前实现使用 Spring AI Alibaba DashScope，测试可以注入 Mock，未来也可替换模型提供商。
 */
public interface AiModelGateway {
    ModelRoute route(String message, List<String> recentMessages);

    /**
     * 生产 Graph 使用的渐进式入口。默认实现保留测试 Mock 和替代模型实现的兼容性；真实网关会
     * 覆盖该方法，并通过 ContextAssembler 生成节点级 Prompt 与 ContextManifest。
     */
    default ModelRoute route(ContextAssemblyRequest request) {
        return route(request.currentMessage(), request.recentMessages());
    }

    String rewrite(String message, List<String> recentMessages, Intent intent);

    default String rewrite(ContextAssemblyRequest request) {
        return rewrite(request.currentMessage(), request.recentMessages(), request.intent());
    }

    ModelAnswer generate(Intent intent, String message, String rewrittenQuery,
                         List<String> recentMessages, List<KnowledgeDoc> evidence,
                         Map<String, Object> businessFacts, Object scopedTools, int candidateVariant);

    default ModelAnswer generate(ContextAssemblyRequest request, Object scopedTools) {
        return generate(request.intent(), request.currentMessage(), request.rewrittenQuery(),
                request.recentMessages(), request.evidence(), request.businessFacts(), scopedTools,
                request.candidateVariant());
    }

    ModelJudge judge(List<AgentDraft> candidates);

    default ModelJudge judge(ContextAssemblyRequest request) { return judge(request.candidates()); }

    /** 使用与客服回答相同的基础模型，根据已发布知识规则评估退款理由充分度。 */
    default RefundReasonScore scoreRefundReason(RefundReasonAssessmentRequest request) {
        throw new UnsupportedOperationException("当前模型网关未实现退款理由评分");
    }
}
