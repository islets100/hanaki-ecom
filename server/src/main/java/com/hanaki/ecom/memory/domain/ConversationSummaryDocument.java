package com.hanaki.ecom.memory.domain;

import java.util.List;
import java.util.Map;

/**
 * 可追溯的滚动会话摘要正文。
 *
 * <p>不同可信度的数据使用不同字段，禁止把“用户说过”“模型归纳”和“业务系统已经完成”混成
 * 一段自然语言。当前项目的模型摘要仅能产生 goals/unresolvedQuestions，confirmedEntities 和
 * completedActions 必须由确定性业务事件补充，不能由摘要模型自行宣称。</p>
 */
public record ConversationSummaryDocument(
        List<String> currentGoals,
        Map<String, ConfirmedEntity> confirmedEntities,
        List<CompletedAction> completedActions,
        List<String> pendingQuestions,
        List<PendingAction> pendingActions,
        List<UserClaim> userClaims,
        List<String> safetyFlags,
        long coveredMessageSeq
) {
    public ConversationSummaryDocument {
        currentGoals = copy(currentGoals);
        confirmedEntities = confirmedEntities == null ? Map.of() : Map.copyOf(confirmedEntities);
        completedActions = completedActions == null ? List.of() : List.copyOf(completedActions);
        pendingQuestions = copy(pendingQuestions);
        pendingActions = pendingActions == null ? List.of() : List.copyOf(pendingActions);
        userClaims = userClaims == null ? List.of() : List.copyOf(userClaims);
        safetyFlags = copy(safetyFlags);
        coveredMessageSeq = Math.max(0, coveredMessageSeq);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().toList();
    }

    public record ConfirmedEntity(String value, long sourceSeq, MemoryTrustLevel trustLevel) {}
    public record CompletedAction(String action, String businessTaskId, String result, long sourceSeq) {}
    public record PendingAction(String type, String businessTaskId) {}
    public record UserClaim(String content, long sourceSeq) {}
}
