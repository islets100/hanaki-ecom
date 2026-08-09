package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.JudgeCandidateScore;
import com.hanaki.ecom.domain.Domain.ModelJudge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeServiceTest {
    @Test
    void safetyScoreBelowVetoThresholdCannotWinEvenWithHigherOtherScores() {
        AiModelGateway model = mock(AiModelGateway.class);
        JudgeCandidateScore unsafe = score("C1", 100, 70);
        JudgeCandidateScore safe = score("C2", 84, 95);
        when(model.judge(anyList())).thenReturn(new ModelJudge(List.of(unsafe, safe), "C2", 10, false, ""));
        JudgeService judge = service(model, 5);

        var outcome = judge.select(List.of(draft("C1"), draft("C2")));

        assertThat(outcome.winner().candidateId()).isEqualTo("C2");
        assertThat(outcome.scores()).anySatisfy(value -> {
            if (value.candidateId().equals("C1")) assertThat(value.total()).isZero();
        });
    }

    @Test
    void lowScoreGapReturnsApplicationSafeFallbackInsteadOfArbitrarilyChoosingFirstPosition() {
        AiModelGateway model = mock(AiModelGateway.class);
        when(model.judge(anyList())).thenReturn(new ModelJudge(
                List.of(score("C1", 86, 95), score("C2", 84, 95)), "C1", 2, false, ""));

        var outcome = service(model, 5).select(List.of(draft("C1"), draft("C2")));

        assertThat(outcome.winner().candidateId()).isEqualTo("SAFE_FALLBACK");
        assertThat(outcome.needsHumanReview()).isTrue();
        assertThat(outcome.fallbackReason()).isEqualTo("LOW_MARGIN_OR_RISK");
    }

    private JudgeService service(AiModelGateway model, int gap) {
        return new JudgeService(model, 62, gap, 80, 30, 20, 15, 15, 10, 10);
    }

    private AgentDraft draft(String id) {
        return new AgentDraft(id, "有证据且可执行的候选答案", List.of("DOC-1"), List.of(), true, 13, 13);
    }

    private JudgeCandidateScore score(String id, int quality, int safety) {
        return new JudgeCandidateScore(id, quality, quality, quality, quality, safety,
                quality, 999, "简短原因", List.of());
    }
}
