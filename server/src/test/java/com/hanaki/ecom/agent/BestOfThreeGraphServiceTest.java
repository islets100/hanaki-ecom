package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.JudgeCandidateScore;
import com.hanaki.ecom.domain.Domain.JudgeOutcome;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.observability.AgentTelemetryService;
import org.h2.jdbcx.JdbcDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BestOfThreeGraphServiceTest {
    @TempDir Path checkpointFolder;

    @Test
    void executesNativeForkJoinPersistsBranchesAndReusesCompletedCandidates() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:evaluation-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        JdbcClient db = JdbcClient.create(source);
        EvaluationStore store = new EvaluationStore(db, new ObjectMapper().findAndRegisterModules());
        CandidateGenerationService generator = mock(CandidateGenerationService.class);
        CountDownLatch parallel = new CountDownLatch(3);
        when(generator.generate(any(), anyInt(), any())).thenAnswer(invocation -> {
            int no = invocation.getArgument(1);
            parallel.countDown();
            if (!parallel.await(2, TimeUnit.SECONDS)) throw new AssertionError("三个候选没有并行重叠执行");
            AgentDraft draft = new AgentDraft("C" + no, "候选答案 " + no, List.of(),
                    List.of("toolResultRef=queryProduct#" + no), true, 12, 12);
            return new CandidateGenerationService.GeneratedCandidate(draft, 100 + no, 20 + no);
        });
        JudgeService judge = mock(JudgeService.class);
        when(judge.select(anyList())).thenAnswer(invocation -> {
            List<AgentDraft> candidates = invocation.getArgument(0);
            return outcome(candidates);
        });
        when(judge.select(any(EvaluationContextSnapshot.class), anyString(), anyList())).thenAnswer(invocation -> {
            List<AgentDraft> candidates = invocation.getArgument(2);
            return outcome(candidates);
        });
        AgentTelemetryService telemetry = mock(AgentTelemetryService.class);
        when(telemetry.observeCandidate(anyString(), anyInt(), any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(3)).get());
        when(telemetry.observeTrace(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(5)).get());
        ExecutorService executor = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(6));
        try {
            BestOfThreeGraphService graph = new BestOfThreeGraphService(generator, store,
                    new EvaluationRequestCache(), judge, telemetry, new CandidateHardValidator(), executor,
                    new SimpleMeterRegistry(), 5, checkpointFolder.toString());
            EvaluationContextSnapshot snapshot = new EvaluationContextSnapshot("B1", "tenant", "user", "C1", "R1",
                    "复杂问题", Intent.PRE_SALE, List.of(),
                    List.of(new KnowledgeDoc("K1", "tenant", "PRE_SALE", "产品手册",
                            "产品能力说明", "v1", .9d)),
                    Map.of("recentOrders", List.of(), "logisticsByOrder", Map.of()), "v1", "r1", "p1", List.of(), Instant.now());
            store.createBatch(snapshot, "trace-1");

            JudgeOutcome first = graph.run(snapshot, "trace-1");
            JudgeOutcome replay = graph.run(snapshot, "trace-1");

            assertThat(first.winner().candidateId()).startsWith("C");
            assertThat(replay.winner().candidateId()).startsWith("C");
            assertThat(db.sql("select count(*) from candidate_answer where status='SUCCEEDED'")
                    .query(Integer.class).single()).isEqualTo(3);
            assertThat(db.sql("select sum(prompt_tokens) from candidate_answer")
                    .query(Integer.class).single()).isEqualTo(306);
            assertThat(db.sql("select count(*) from evaluation_snapshot")
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(db.sql("select count(*) from candidate_attempt")
                    .query(Integer.class).single()).isEqualTo(3);
            assertThat(db.sql("select count(*) from judge_attempt where status='SUCCEEDED'")
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(db.sql("select count(distinct candidate_profile) from candidate_answer where candidate_no>0")
                    .query(Integer.class).single()).isEqualTo(3);
            assertThat(db.sql("select snapshot_hash from evaluation_snapshot")
                    .query(String.class).single()).hasSize(64);
            verify(generator, times(3)).generate(any(), anyInt(), any());
        } finally { executor.shutdownNow(); }
    }

    private static JudgeOutcome outcome(List<AgentDraft> candidates) {
        AgentDraft winner = candidates.getFirst();
        JudgeCandidateScore score = new JudgeCandidateScore(winner.candidateId(),
                15, 15, 15, 15, 15, 15, 90, "evidence-backed", List.of());
        return new JudgeOutcome(winner, List.of(score), 8, false, null);
    }
}
