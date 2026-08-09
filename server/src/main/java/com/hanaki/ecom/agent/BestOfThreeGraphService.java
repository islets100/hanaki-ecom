package com.hanaki.ecom.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.NodeAggregationStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.CandidateProfile;
import com.hanaki.ecom.domain.Domain.CandidateRunOutput;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.EvaluationDecision;
import com.hanaki.ecom.domain.Domain.EvaluationTriggerMode;
import com.hanaki.ecom.domain.Domain.JudgeOutcome;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.observability.AgentTelemetryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 可恢复的 Best-of-3 评审子 Graph。三个分支写入固定的 candidate0Ref/1Ref/2Ref，
 * 不再依赖并发列表追加顺序；数据库记录是事实源，Graph checkpoint 只保存引用。
 *
 * <p>完整状态流为：创建批次 → 冻结事实快照 → 并行生成三个候选 → 固定引用聚合 → 硬校验 →
 * 独立 Judge → 服务端选择并持久化。数据库保存每一次 candidate/judge attempt 和单调状态版本，
 * 因此进程重启后可以复用已经完成的分支，而不是重新调用模型或覆盖旧审计记录。</p>
 *
 * <p>并行只发生在互相隔离的只读候选分支。快照冻结之后不能再读取变化中的业务事实；候选无写
 * 工具；Judge 无任何工具；最终写入也只是评审审计，不会代替退款、取消订单等业务确认流程。</p>
 */
@Service
public class BestOfThreeGraphService {
    private static final List<String> CANDIDATE_NODES = List.of("candidate_1", "candidate_2", "candidate_3");
    private final CandidateGenerationService generator;
    private final EvaluationStore evaluations;
    private final EvaluationRequestCache requestCache;
    private final JudgeService judge;
    private final AgentTelemetryService telemetry;
    private final CandidateHardValidator validator;
    private final CandidateSimilarityService similarity;
    private final EvaluationConcurrencyGuard concurrency;
    private final MeterRegistry meters;
    private final ExecutorService candidateExecutor;
    private final ExecutorService modelExecutor;
    private final int candidateTimeoutSeconds;
    private final int judgeTimeoutSeconds;
    private final int batchTimeoutSeconds;
    private final int maxCandidateAttempts;
    private final int maxJudgeAttempts;
    private final int minimumSuccess;
    private final double similarityThreshold;
    private final int qualityThreshold;
    private final int scoreGapThreshold;
    private final String judgeModel;
    private final long maxTenantDailyTokens;
    private final CompiledGraph evaluationGraph;
    private final CompiledGraph candidateGraph;

    @Autowired
    public BestOfThreeGraphService(CandidateGenerationService generator, EvaluationStore evaluations,
                                   EvaluationRequestCache requestCache, JudgeService judge,
                                   AgentTelemetryService telemetry, CandidateHardValidator validator,
                                   CandidateSimilarityService similarity, EvaluationConcurrencyGuard concurrency,
                                   @Qualifier("candidateExecutor") ExecutorService candidateExecutor,
                                   @Qualifier("evaluationModelExecutor") ExecutorService modelExecutor,
                                   MeterRegistry meters,
                                   @Value("${agent.evaluation.timeout.candidate-seconds:8}") int candidateTimeoutSeconds,
                                   @Value("${agent.evaluation.timeout.judge-seconds:5}") int judgeTimeoutSeconds,
                                   @Value("${agent.evaluation.timeout.batch-seconds:15}") int batchTimeoutSeconds,
                                   @Value("${agent.evaluation.max-candidate-attempts:2}") int maxCandidateAttempts,
                                   @Value("${agent.evaluation.max-judge-attempts:2}") int maxJudgeAttempts,
                                   @Value("${agent.evaluation.minimum-successful-candidates:2}") int minimumSuccess,
                                   @Value("${agent.evaluation.similarity-threshold:0.92}") double similarityThreshold,
                                   @Value("${agent.judge.minimum-score:75}") int qualityThreshold,
                                   @Value("${agent.judge.minimum-gap:5}") int scoreGapThreshold,
                                   @Value("${agent.judge.model:qwen-max}") String judgeModel,
                                   @Value("${agent.evaluation.budget.max-tenant-daily-tokens:2000000}") long maxTenantDailyTokens,
                                   @Value("${agent.checkpoint.directory:./data/graph-checkpoints}") String checkpointDirectory) {
        this.generator = generator;
        this.evaluations = evaluations;
        this.requestCache = requestCache;
        this.judge = judge;
        this.telemetry = telemetry;
        this.validator = validator;
        this.similarity = similarity;
        this.concurrency = concurrency;
        this.candidateExecutor = candidateExecutor;
        this.modelExecutor = modelExecutor;
        this.meters = meters;
        /*
         * 三层截止时间分别控制单候选、Judge 和整个批次。所有配置至少为 1，重试次数也有界，
         * 从而保证模型不响应时不会无限占用线程或租户并发许可。
         */
        this.candidateTimeoutSeconds = Math.max(1, candidateTimeoutSeconds);
        this.judgeTimeoutSeconds = Math.max(1, judgeTimeoutSeconds);
        this.batchTimeoutSeconds = Math.max(1, batchTimeoutSeconds);
        this.maxCandidateAttempts = Math.max(1, maxCandidateAttempts);
        this.maxJudgeAttempts = Math.max(1, maxJudgeAttempts);
        this.minimumSuccess = Math.max(1, minimumSuccess);
        this.similarityThreshold = Math.max(0d, Math.min(1d, similarityThreshold));
        this.qualityThreshold = qualityThreshold;
        this.scoreGapThreshold = scoreGapThreshold;
        this.judgeModel = judgeModel;
        this.maxTenantDailyTokens = Math.max(1L, maxTenantDailyTokens);
        this.evaluationGraph = compileEvaluation(checkpointDirectory);
        this.candidateGraph = compileCandidate(checkpointDirectory);
    }

    /** 兼容直接构造的单元测试；生产环境使用上面的两个独立有界执行器。 */
    BestOfThreeGraphService(CandidateGenerationService generator, EvaluationStore evaluations,
                            EvaluationRequestCache requestCache, JudgeService judge,
                            AgentTelemetryService telemetry, CandidateHardValidator validator,
                            ExecutorService candidateExecutor, MeterRegistry meters,
                            int timeoutSeconds, String checkpointDirectory) {
        this(generator, evaluations, requestCache, judge, telemetry, validator,
                new CandidateSimilarityService(), new EvaluationConcurrencyGuard(20, 3, 40),
                candidateExecutor, null, meters, timeoutSeconds, 5, 15, 2, 2, 2, .92d,
                75, 5, "test-judge", 2_000_000L, checkpointDirectory);
    }

    /** 兼容旧调用：按 PRE_GENERATION 评审处理，并显式记录 LEGACY_CALL 触发原因。 */
    public JudgeOutcome run(EvaluationContextSnapshot snapshot, String traceId) {
        return run(snapshot, traceId, new EvaluationDecision(true, EvaluationTriggerMode.PRE_GENERATION,
                "LEGACY_CALL", "best-of-three-v2"));
    }

    /**
     * 执行或恢复一个评审批次。
     *
     * <p>首先查数据库终态保证 batchId 幂等；其次执行租户日 Token 配额和并发许可保护。只有通过
     * 两道守卫后才打开批次请求缓存、运行 Graph。请求缓存和批次计时器在 finally 中关闭，即使
     * 任意节点异常也不会泄漏。配额/并发不足不抛 5xx，而是创建可审计的 SAFE_FALLBACK 批次。</p>
     */
    public JudgeOutcome run(EvaluationContextSnapshot snapshot, String traceId, EvaluationDecision decision) {
        // 已完成批次直接返回持久化 Outcome；重放同一 message/run 不会再次消耗模型 Token。
        Optional<JudgeOutcome> completed = evaluations.completedOutcome(snapshot.evaluationBatchId());
        if (completed.isPresent()) {
            Counter.builder("agent.evaluation.batch").tag("result", "reused").register(meters).increment();
            return completed.get();
        }
        // 日配额在申请并发许可前检查，避免配额已用尽的租户占用全局槽位。
        if (evaluations.tenantTokensToday(snapshot.tenantId()) >= maxTenantDailyTokens)
            return guardFallback(snapshot, traceId, decision, "TENANT_DAILY_TOKEN_BUDGET",
                    "当前租户今日评审 Token 配额已用尽");
        try (EvaluationConcurrencyGuard.Permit permit = concurrency.tryBatch(snapshot.tenantId())) {
            if (!permit.acquired()) return guardFallback(snapshot, traceId, decision,
                    "CONCURRENCY_LIMIT", "评审并发额度已用尽，已安全降级");
            Counter.builder("agent.evaluation.batch").tag("result", "started").register(meters).increment();
            Timer.Sample batchTimer = Timer.start(meters);
            openRequestCache(snapshot, traceId);
            try {
                /*
                 * threadId 使用稳定 evaluationBatchId，使 FileSystemSaver 能恢复相同批次。ALL_OF 要求
                 * 三个候选节点都结束后才进入聚合；失败分支也会产出 CandidateRunOutput，而不是让
                 * Fork-Join 因缺少一个列表元素产生顺序不确定性。
                 */
                RunnableConfig.Builder config = RunnableConfig.builder()
                        .threadId(snapshot.evaluationBatchId())
                        .defaultParallelAggregationStrategy(NodeAggregationStrategy.ALL_OF);
                for (String node : CANDIDATE_NODES) config.addParallelNodeExecutor(node, candidateExecutor);
                OverAllState result = evaluationGraph.invoke(Map.of("snapshot", snapshot, "traceId", traceId,
                                "evaluationDecision", decision,
                                "deadlineEpochMs", System.currentTimeMillis() + batchTimeoutSeconds * 1000L), config.build())
                        .orElseThrow(() -> new ModelCallException("多候选评审子 Graph 没有返回状态"));
                JudgeOutcome outcome = result.<JudgeOutcome>value("judgeOutcome")
                        .orElseThrow(() -> new ModelCallException("多候选评审没有返回 JudgeOutcome"));
                recordBatchMetrics(outcome);
                return outcome;
            } catch (RuntimeException error) {
                Counter.builder("agent.evaluation.batch").tag("result", "failed").register(meters).increment();
                throw error;
            } finally {
                batchTimer.stop(Timer.builder("agent.evaluation.duration").register(meters));
                requestCache.close(snapshot.evaluationBatchId());
            }
        }
    }

    /**
     * 编译批次级 Graph。候选结果使用三个固定 ReplaceStrategy 引用，而不是并发 append 到 List；
     * 这样 candidate_2 即使最先完成，仍然只能写 candidate1Ref，聚合排序始终稳定。
     */
    private CompiledGraph compileEvaluation(String checkpointDirectory) {
        try {
            Path folder = Path.of(checkpointDirectory).toAbsolutePath().normalize().resolve("evaluation");
            Files.createDirectories(folder);
            KeyStrategyFactory keys = () -> {
                Map<String, KeyStrategy> map = new HashMap<>();
                for (String key : List.of("snapshot", "traceId", "evaluationDecision", "candidate0Ref",
                        "candidate1Ref", "candidate2Ref", "candidateRuns", "validatedCandidates",
                        "judgeOutcome", "judgeAttemptId", "candidateSetHash", "deadlineEpochMs"))
                    map.put(key, new ReplaceStrategy());
                return map;
            };
            // 节点名称也是 checkpoint/遥测协议的一部分，升级流程时应同步提升 Graph 版本。
            StateGraph stateGraph = new StateGraph("best-of-three-evaluation-v2", keys)
                    .addNode("create_batch", node_async(this::createBatchNode))
                    .addNode("freeze_snapshot", node_async(this::freezeSnapshotNode))
                    .addNode("start_candidates", node_async(this::startCandidatesNode))
                    .addNode("candidate_1", node_async(state -> candidateNode(state, 1)))
                    .addNode("candidate_2", node_async(state -> candidateNode(state, 2)))
                    .addNode("candidate_3", node_async(state -> candidateNode(state, 3)))
                    .addNode("aggregate_candidates", node_async(this::aggregateNode))
                    .addNode("hard_validate", node_async(this::hardValidateNode))
                    .addNode("judge", node_async(this::judgeNode))
                    .addNode("select_winner", node_async(this::selectWinnerNode))
                    .addEdge(StateGraph.START, "create_batch")
                    .addEdge("create_batch", "freeze_snapshot")
                    .addEdge("freeze_snapshot", "start_candidates")
                    .addEdge("start_candidates", CANDIDATE_NODES)
                    .addEdge(CANDIDATE_NODES, "aggregate_candidates")
                    .addEdge("aggregate_candidates", "hard_validate")
                    .addEdge("hard_validate", "judge")
                    .addEdge("judge", "select_winner")
                    .addEdge("select_winner", StateGraph.END);
            FileSystemSaver saver = FileSystemSaver.builder().targetFolder(folder).build();
            return stateGraph.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(saver).build()).build());
        } catch (Exception error) {
            throw new IllegalStateException("无法编译 Best-of-3 评审子 Graph", error);
        }
    }

    /**
     * 编译单候选隔离 SubGraph。每次 attempt 使用独立 candidateRunId 作为 threadId，checkpoint 不会
     * 在三个候选间互相覆盖；进入模型前再次 verifySnapshot，防止被篡改的恢复状态参与生成。
     */
    private CompiledGraph compileCandidate(String checkpointDirectory) {
        try {
            Path folder = Path.of(checkpointDirectory).toAbsolutePath().normalize().resolve("evaluation-candidates");
            Files.createDirectories(folder);
            KeyStrategyFactory keys = () -> Map.of("snapshot", new ReplaceStrategy(),
                    "candidateNo", new ReplaceStrategy(), "generatedCandidate", new ReplaceStrategy());
            StateGraph stateGraph = new StateGraph("isolated-candidate-run-v2", keys)
                    .addNode("generate_candidate", node_async(state -> {
                        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
                        int candidateNo = state.<Number>value("candidateNo").orElseThrow().intValue();
                        evaluations.verifySnapshot(snapshot);
                        return Map.of("generatedCandidate", generator.generate(snapshot,
                                candidateNo, requestCache.get(snapshot.evaluationBatchId())));
                    }))
                    .addEdge(StateGraph.START, "generate_candidate")
                    .addEdge("generate_candidate", StateGraph.END);
            FileSystemSaver saver = FileSystemSaver.builder().targetFolder(folder).build();
            return stateGraph.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(saver).build()).build());
        } catch (Exception error) {
            throw new IllegalStateException("无法编译候选隔离 SubGraph", error);
        }
    }

    /** 持久化批次身份、触发原因和当时的质量阈值；重复创建必须与原租户/用户/run 身份一致。 */
    private Map<String, Object> createBatchNode(OverAllState state) {
        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
        EvaluationDecision decision = state.<EvaluationDecision>value("evaluationDecision").orElseThrow();
        evaluations.createBatch(snapshot, state.value("traceId", ""), decision, qualityThreshold, scoreGapThreshold);
        return Map.of("snapshot", snapshot);
    }

    /** 深度冻结事实并写入规范化 SHA-256；后续所有候选只读取这一个 sealed snapshot。 */
    private Map<String, Object> freezeSnapshotNode(OverAllState state) {
        EvaluationContextSnapshot sealed = evaluations.freezeSnapshot(
                state.<EvaluationContextSnapshot>value("snapshot").orElseThrow());
        evaluations.verifySnapshot(sealed);
        return Map.of("snapshot", sealed);
    }

    /** 以带版本条件的状态转换进入 CANDIDATES_RUNNING，重复恢复不会让状态倒退。 */
    private Map<String, Object> startCandidatesNode(OverAllState state) {
        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "CANDIDATES_RUNNING", "SNAPSHOT_READY");
        return Map.of("snapshot", snapshot);
    }

    /**
     * 执行一个固定编号候选分支。
     *
     * <p>先复用数据库中已成功的 candidateNo，未完成时才创建新的 attempt。每次 attempt 独立记录
     * profile、runId、耗时、Token 与错误；失败可在上限内重试，成功立即返回。候选许可、单调用
     * timeout 和批次 deadline 三者同时约束，任意一个不满足都不会继续放大模型流量。</p>
     */
    private Map<String, Object> candidateNode(OverAllState state, int candidateNo) {
        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
        String traceId = state.value("traceId", "");
        CandidateProfile profile = CandidateProfile.forCandidate(candidateNo);
        long deadlineEpochMs = state.<Number>value("deadlineEpochMs").map(Number::longValue)
                .orElse(Long.MAX_VALUE);
        // candidateNo 是幂等槽位：恢复时只复用这个槽位最近一次 SUCCEEDED 的完整草稿。
        Optional<EvaluationStore.RecoveredCandidate> completed = evaluations.completedCandidate(
                snapshot.evaluationBatchId(), candidateNo);
        if (completed.isPresent()) {
            EvaluationStore.RecoveredCandidate recovered = completed.get();
            CandidateRunOutput reused = new CandidateRunOutput(candidateNo, recovered.candidateRunId(),
                    recovered.attemptId(), recovered.profile(), recovered.draft(), "SUCCEEDED", null, null,
                    recovered.elapsedMs(), recovered.promptTokens(), recovered.completionTokens(), true);
            Counter.builder("agent.evaluation.candidate").tag("candidate", Integer.toString(candidateNo))
                    .tag("result", "reused").register(meters).increment();
            return Map.of(candidateRef(candidateNo), reused);
        }

        CandidateRunOutput lastFailure = null;
        for (int retry = 0; retry < maxCandidateAttempts; retry++) {
            Optional<EvaluationStore.CandidateAttempt> started = evaluations.startCandidateAttempt(
                    snapshot, candidateNo, profile, maxCandidateAttempts);
            if (started.isEmpty()) break;
            EvaluationStore.CandidateAttempt attempt = started.get();
            long startedAt = System.nanoTime();
            try (EvaluationConcurrencyGuard.Permit permit = concurrency.tryCandidate()) {
                // permit 未获得时把本 attempt 记为失败，由有限重试/降级继续处理，不阻塞等待队列。
                if (System.currentTimeMillis() >= deadlineEpochMs)
                    throw new java.util.concurrent.TimeoutException("评审批次已超过截止时间");
                if (!permit.acquired()) throw new RejectedExecutionException("全局候选并发额度已用尽");
                CandidateGenerationService.GeneratedCandidate generated = invokeWithTimeout(snapshot, candidateNo,
                        attempt.candidateRunId(), traceId, profile, deadlineEpochMs);
                long elapsedMs = elapsed(startedAt);
                evaluations.completeCandidateAttempt(attempt.id(), generated.draft(), elapsedMs,
                        generated.promptTokens(), generated.completionTokens());
                CandidateRunOutput output = new CandidateRunOutput(candidateNo, attempt.candidateRunId(), attempt.id(),
                        profile, generated.draft(), "GENERATED", null, null, elapsedMs,
                        generated.promptTokens(), generated.completionTokens(), false);
                recordCandidateMetrics(candidateNo, output);
                return Map.of(candidateRef(candidateNo), output);
            } catch (Exception error) {
                // 只保存解包后的根因与截断消息；Future/CompletionException 包装层不进入业务审计。
                Throwable root = unwrap(error);
                long elapsedMs = elapsed(startedAt);
                evaluations.failCandidateAttempt(attempt.id(), root, elapsedMs);
                evaluations.saveCandidateFailure(snapshot.evaluationBatchId(), candidateNo, attempt.candidateRunId(),
                        root, elapsedMs);
                lastFailure = new CandidateRunOutput(candidateNo, attempt.candidateRunId(), attempt.id(),
                        profile, null, "FAILED", root.getClass().getName(), safeMessage(root), elapsedMs,
                        0, 0, false);
                recordCandidateMetrics(candidateNo, lastFailure);
            }
        }
        CandidateRunOutput exhausted = lastFailure == null ? new CandidateRunOutput(candidateNo,
                snapshot.evaluationBatchId() + "-candidate-" + candidateNo, null, profile, null,
                "FAILED", "ATTEMPTS_EXHAUSTED", "候选重试次数已用尽", 0, 0, 0, false) : lastFailure;
        return Map.of(candidateRef(candidateNo), exhausted);
    }

    /**
     * 在专用有界模型执行器中调用候选 SubGraph。有效 timeout 取“单候选上限”和“批次剩余时间”
     * 的较小值。超时后只放弃结果、不向正在执行的线程发送 interrupt：模型调用的失败审计会在同一线程
     * 写入 H2，强制中断可能关闭 JVM 共享的 NIO 文件通道，进而让整个应用数据库不可用。模型执行器本身
     * 已有线程数和队列上限，迟到结果不会进入当前批次，也不会突破并发上限。
     */
    private CandidateGenerationService.GeneratedCandidate invokeWithTimeout(EvaluationContextSnapshot snapshot,
                                                                              int candidateNo,
                                                                              String candidateRunId,
                                                                              String traceId,
                                                                              CandidateProfile profile,
                                                                              long deadlineEpochMs) throws Exception {
        java.util.concurrent.Callable<CandidateGenerationService.GeneratedCandidate> call = () ->
                telemetry.observeCandidate(traceId, candidateNo,
                        Map.of("evaluationBatchId", snapshot.evaluationBatchId(), "candidateRunId", candidateRunId,
                                "strategy", profile.name(), "snapshotHash", snapshot.snapshotHash()),
                        () -> invokeCandidateGraph(snapshot, candidateNo, candidateRunId));
        if (modelExecutor == null) return call.call();
        Future<CandidateGenerationService.GeneratedCandidate> future = modelExecutor.submit(call);
        long remainingMillis = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
        long timeoutMillis = Math.min(candidateTimeoutSeconds * 1000L, remainingMillis);
        try { return future.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (Exception error) {
            future.cancel(false);
            throw error;
        }
    }

    /** 固定读取三个引用并按 candidateNo 排序；FAILED 分支也必须存在，保证审计集合完整。 */
    private Map<String, Object> aggregateNode(OverAllState state) {
        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
        List<CandidateRunOutput> runs = new ArrayList<>(3);
        for (int candidateNo = 1; candidateNo <= 3; candidateNo++)
            state.<CandidateRunOutput>value(candidateRef(candidateNo)).ifPresent(runs::add);
        runs.sort(java.util.Comparator.comparingInt(CandidateRunOutput::candidateNo));
        if (runs.size() != 3) throw new IllegalStateException("Fork-Join 未返回三个固定候选引用");
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "CANDIDATES_READY", "CANDIDATES_RUNNING");
        return Map.of("candidateRuns", List.copyOf(runs));
    }

    @SuppressWarnings("unchecked")
    /**
     * 对所有非失败草稿执行确定性硬校验。被拒候选保留原答案用于内部审计，但 safe 强制改为 false，
     * 状态改为 REJECTED，Judge 只会看到 SUCCEEDED 集合。每个候选的检查码和违规码同时持久化。
     */
    private Map<String, Object> hardValidateNode(OverAllState state) {
        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "HARD_VALIDATING", "CANDIDATES_READY");
        List<CandidateRunOutput> runs = state.value("candidateRuns")
                .map(value -> (List<CandidateRunOutput>) value).orElse(List.of());
        List<CandidateRunOutput> validated = new ArrayList<>(runs.size());
        int success = 0;
        for (CandidateRunOutput run : runs) {
            if (run.draft() == null || "FAILED".equals(run.status())) {
                validated.add(run);
                continue;
            }
            CandidateHardValidator.Validation validation = validator.validate(snapshot, run.draft());
            AgentDraft draft = validation.accepted() ? run.draft() : new AgentDraft(run.draft().candidateId(),
                    run.draft().answer(), run.draft().evidence(), run.draft().toolResults(), false,
                    run.draft().completeness(), run.draft().clarity());
            evaluations.saveCandidate(snapshot.evaluationBatchId(), run.candidateNo(), run.candidateRunId(),
                    run.attemptId(), run.profile(), draft, run.elapsedMs(), validation.auditEntries(),
                    run.promptTokens(), run.completionTokens());
            String status = validation.accepted() ? "SUCCEEDED" : "REJECTED";
            if (validation.accepted()) success++;
            validated.add(new CandidateRunOutput(run.candidateNo(), run.candidateRunId(), run.attemptId(),
                    run.profile(), draft, status, null,
                    validation.accepted() ? null : String.join(",", validation.violations()), run.elapsedMs(),
                    run.promptTokens(), run.completionTokens(), run.reused()));
        }
        evaluations.updateSuccessfulCount(snapshot.evaluationBatchId(), success);
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "HARD_VALIDATED", "HARD_VALIDATING");
        return Map.of("validatedCandidates", List.copyOf(validated));
    }

    @SuppressWarnings("unchecked")
    /**
     * 根据成功数量与候选多样性决定是否调用独立 Judge。
     *
     * <p>零候选使用固定安全回答；少于 minimumSuccess 或过度相似时使用确定性降级评分；只有数量和
     * 多样性都满足时才创建 judge_attempt。Judge attempt 同样有独立重试、超时和集合哈希，确保
     * 恢复时不会把旧候选集合的评分套到新集合上。</p>
     */
    private Map<String, Object> judgeNode(OverAllState state) {
        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
        String traceId = state.value("traceId", "");
        long deadlineEpochMs = state.<Number>value("deadlineEpochMs").map(Number::longValue)
                .orElse(Long.MAX_VALUE);
        List<CandidateRunOutput> runs = state.value("validatedCandidates")
                .map(value -> (List<CandidateRunOutput>) value).orElse(List.of());
        List<AgentDraft> successful = runs.stream().filter(run -> "SUCCEEDED".equals(run.status()))
                .map(CandidateRunOutput::draft).filter(Objects::nonNull).toList();
        // 集合哈希按 candidateId 排序后计算，与并行完成顺序无关。
        String setHash = evaluations.candidateSetHashFromDrafts(successful);
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "JUDGING", "HARD_VALIDATED");

        JudgeOutcome outcome;
        String successfulAttemptId = null;
        if (successful.isEmpty()) {
            outcome = safeFallback("ALL_CANDIDATES_FAILED", "三个候选分支全部失败或被硬规则淘汰");
        } else if (successful.size() < minimumSuccess) {
            outcome = judge.deterministicFallback(successful, "MINIMUM_SUCCESSFUL_CANDIDATES_NOT_MET");
        } else if (similarity.maximumSimilarity(successful) >= similarityThreshold) {
            Counter.builder("agent.evaluation.fallback").tag("reason", "low_candidate_diversity")
                    .register(meters).increment();
            outcome = judge.deterministicFallback(successful, "LOW_CANDIDATE_DIVERSITY");
        } else {
            Throwable lastFailure = null;
            JudgeOutcome selected = null;
            for (int attempt = 1; attempt <= maxJudgeAttempts && selected == null; attempt++) {
                if (System.currentTimeMillis() >= deadlineEpochMs) {
                    lastFailure = new ModelCallException("评审批次已超过截止时间");
                    break;
                }
                Optional<EvaluationStore.JudgeAttempt> started = evaluations.startJudgeAttempt(
                        snapshot, setHash, maxJudgeAttempts, judgeModel);
                if (started.isEmpty()) break;
                EvaluationStore.JudgeAttempt judgeAttempt = started.get();
                long startedAt = System.nanoTime();
                try {
                    selected = invokeJudgeWithTimeout(traceId, snapshot, successful, judgeAttempt,
                            setHash, deadlineEpochMs);
                    evaluations.completeJudgeAttempt(judgeAttempt.id(), selected, elapsed(startedAt));
                    successfulAttemptId = judgeAttempt.id();
                } catch (Exception error) {
                    Throwable root = unwrap(error);
                    evaluations.failJudgeAttempt(judgeAttempt.id(), root, elapsed(startedAt));
                    lastFailure = root;
                }
            }
            outcome = selected != null ? selected : judge.deterministicFallback(successful,
                    "JUDGE_RETRY_EXHAUSTED:" + (lastFailure == null ? "NO_ATTEMPT" : lastFailure.getClass().getSimpleName()));
        }
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "JUDGED", "JUDGING");
        Map<String, Object> result = new HashMap<>();
        result.put("judgeOutcome", outcome);
        result.put("candidateSetHash", setHash);
        if (successfulAttemptId != null) result.put("judgeAttemptId", successfulAttemptId);
        return result;
    }

    /**
     * 提交最终选择。SAFE_FALLBACK 也作为 candidateNo=0 保存，确保 winner_id 始终能追溯到一条候选
     * 记录；之后按单调状态机进入 SELECTED/COMPLETED，并在同一事务中保存分项评分和知识候选事件。
     */
    private Map<String, Object> selectWinnerNode(OverAllState state) {
        EvaluationContextSnapshot snapshot = state.<EvaluationContextSnapshot>value("snapshot").orElseThrow();
        JudgeOutcome outcome = state.<JudgeOutcome>value("judgeOutcome").orElseThrow();
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "SELECTING", "JUDGED");
        if ("SAFE_FALLBACK".equals(outcome.winner().candidateId())) {
            evaluations.saveCandidate(snapshot.evaluationBatchId(), 0,
                    snapshot.evaluationBatchId() + "-safe-fallback", outcome.winner(), 0,
                    List.of(outcome.fallbackReason() == null ? "SAFE_FALLBACK" : outcome.fallbackReason()), 0, 0);
        }
        evaluations.advanceStatus(snapshot.evaluationBatchId(), "SELECTED", "SELECTING");
        evaluations.complete(snapshot, outcome, state.value("traceId", ""),
                state.<String>value("judgeAttemptId").orElse(null), state.value("candidateSetHash", ""));
        if (outcome.fallbackReason() != null && !outcome.fallbackReason().isBlank())
            Counter.builder("agent.evaluation.fallback").tag("reason", metricTag(outcome.fallbackReason()))
                    .register(meters).increment();
        return Map.of("judgeOutcome", outcome);
    }

    /** 用 candidateRunId 隔离单候选 checkpoint，并要求返回强类型 GeneratedCandidate。 */
    private CandidateGenerationService.GeneratedCandidate invokeCandidateGraph(EvaluationContextSnapshot snapshot,
                                                                                 int candidateNo,
                                                                                 String candidateRunId) {
        OverAllState result = candidateGraph.invoke(Map.of("snapshot", snapshot, "candidateNo", candidateNo),
                        RunnableConfig.builder().threadId(candidateRunId).build())
                .orElseThrow(() -> new ModelCallException("候选 SubGraph 没有返回状态"));
        return result.<CandidateGenerationService.GeneratedCandidate>value("generatedCandidate")
                .orElseThrow(() -> new ModelCallException("候选 SubGraph 没有返回结构化答案"));
    }

    /** Judge 无工具且候选顺序已随机化；超时计算同样不能超过批次剩余时间。 */
    private JudgeOutcome invokeJudgeWithTimeout(String traceId, EvaluationContextSnapshot snapshot,
                                                 List<AgentDraft> successful,
                                                 EvaluationStore.JudgeAttempt judgeAttempt,
                                                 String setHash, long deadlineEpochMs) throws Exception {
        java.util.concurrent.Callable<JudgeOutcome> call = () -> telemetry.observeTrace(traceId,
                "candidate.judge", "MODEL",
                Map.of("evaluationBatchId", snapshot.evaluationBatchId(), "candidateCount", successful.size(),
                        "judgeAttempt", judgeAttempt.attemptNo(), "candidateSetHash", setHash),
                Map.of("judgeToolsEnabled", false, "candidateOrderRandomized", true),
                () -> judge.select(snapshot, traceId, successful));
        if (modelExecutor == null) return call.call();
        Future<JudgeOutcome> future = modelExecutor.submit(call);
        long remainingMillis = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
        long timeoutMillis = Math.min(judgeTimeoutSeconds * 1000L, remainingMillis);
        try { return future.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (Exception error) {
            // 与候选生成保持相同语义：丢弃迟到结果，但不以 interrupt 破坏共享 H2 文件通道。
            future.cancel(false);
            throw error;
        }
    }

    /**
     * 配额或并发守卫触发时也创建、冻结并完成一条真实评审记录，而不是只返回内存答案。这样运营侧
     * 能区分“模型质量降级”和“容量保护降级”，并可按 reason 统计扩容需求。
     */
    private JudgeOutcome guardFallback(EvaluationContextSnapshot draft, String traceId, EvaluationDecision decision,
                                       String reason, String audit) {
        Counter.builder("agent.evaluation.fallback").tag("reason", metricTag(reason)).register(meters).increment();
        evaluations.createBatch(draft, traceId, decision, qualityThreshold, scoreGapThreshold);
        EvaluationContextSnapshot snapshot = evaluations.freezeSnapshot(draft);
        JudgeOutcome outcome = safeFallback(reason, audit);
        evaluations.saveCandidate(snapshot.evaluationBatchId(), 0, snapshot.evaluationBatchId() + "-overload-fallback",
                outcome.winner(), 0, List.of(reason), 0, 0);
        evaluations.complete(snapshot, outcome, traceId);
        return outcome;
    }

    /** 构造不包含订单、金额或政策结论的最小安全回答；reason 进入结构化 Outcome 和工具审计。 */
    private JudgeOutcome safeFallback(String reason, String audit) {
        AgentDraft fallback = new AgentDraft("SAFE_FALLBACK",
                "当前信息不足以生成可验证的可靠答案。请补充具体商品或本人订单信息，或者回复“转人工”由客服继续处理。",
                List.of(), List.of(audit), true, 5, 10);
        return new JudgeOutcome(fallback, List.of(), 0, true, reason);
    }

    @SuppressWarnings("unchecked")
    /**
     * 用冻结的订单/物流事实预热批次级只读缓存。缓存键与 ScopedCommerceTools 使用同一构造函数，
     * 因此三个候选读取同一工具参数时直接复用快照，不会各自访问数据库或观察到不同时间点的状态。
     */
    private void openRequestCache(EvaluationContextSnapshot snapshot, String traceId) {
        List<OrderSummary> orders = frozenOrders(snapshot);
        OrderQueryScope orderScope = OrderQueryScope.fromBusinessFacts(snapshot.businessFacts());
        Map<String, Object> initial = new HashMap<>();
        initial.put(ScopedCommerceTools.requestCacheKey(snapshot.intent(), snapshot.tenantId(), snapshot.userId(),
                traceId, "recentOrders", orderScope.cacheArguments()), orders);
        Map<String, Object> logistics = (Map<String, Object>) snapshot.businessFacts()
                .getOrDefault("logisticsByOrder", Map.of());
        logistics.forEach((orderId, value) -> initial.put(ScopedCommerceTools.requestCacheKey(snapshot.intent(),
                snapshot.tenantId(), snapshot.userId(), traceId, "queryLogistics", Map.of("orderId", orderId)), value));
        requestCache.open(snapshot.evaluationBatchId(), Map.copyOf(initial));
    }

    /** 将可持久化的订单投影恢复成只读工具对象，保证三个候选读取同一批次快照。 */
    private List<OrderSummary> frozenOrders(EvaluationContextSnapshot snapshot) {
        Object raw = snapshot.businessFacts().get("recentOrders");
        if (!(raw instanceof List<?> values)) return List.of();
        List<OrderSummary> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof OrderSummary order) {
                result.add(order);
                continue;
            }
            if (!(value instanceof Map<?, ?> item)) continue;
            result.add(new OrderSummary(text(item, "orderId"), "", snapshot.tenantId(), snapshot.userId(),
                    text(item, "productId"), text(item, "productName"), text(item, "sku"),
                    decimal(item.get("amount")), text(item, "orderStatus"), text(item, "paymentStatus"),
                    text(item, "logisticsStatus"), text(item, "storeName"),
                    instant(item.get("plannedShipAt")), instant(item.get("estimatedArrivalAt")),
                    instant(item.get("createdAt"))));
        }
        return List.copyOf(result);
    }

    private String text(Map<?, ?> item, String key) {
        Object value = item.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal number) return number;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (RuntimeException ignored) { return BigDecimal.ZERO; }
    }

    private Instant instant(Object value) {
        if (value instanceof Instant time) return time;
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Instant.parse(String.valueOf(value)); }
        catch (RuntimeException ignored) { return null; }
    }

    private String candidateRef(int candidateNo) { return "candidate" + (candidateNo - 1) + "Ref"; }
    private long elapsed(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    /** 指标标签只使用有限枚举，不放 batchId、tenantId 或异常消息，避免高基数时序爆炸。 */
    private void recordBatchMetrics(JudgeOutcome outcome) {
        Counter.builder("agent.evaluation.batch")
                .tag("result", outcome.needsHumanReview() ? "needs_review" : "selected")
                .register(meters).increment();
        DistributionSummary.builder("agent.evaluation.score.gap").register(meters).record(outcome.scoreGap());
    }

    /** 分候选记录结果、耗时和输入/输出 Token；原问题和答案正文只进入受控审计库，不进入指标标签。 */
    private void recordCandidateMetrics(int candidateNo, CandidateRunOutput output) {
        String candidate = Integer.toString(candidateNo);
        String result = output.status().toLowerCase(java.util.Locale.ROOT);
        Counter.builder("agent.evaluation.candidate").tag("candidate", candidate).tag("result", result)
                .register(meters).increment();
        Timer.builder("agent.evaluation.candidate.duration").tag("candidate", candidate).tag("result", result)
                .register(meters).record(output.elapsedMs(), TimeUnit.MILLISECONDS);
        Counter.builder("agent.evaluation.candidate.tokens").tag("candidate", candidate).tag("direction", "input")
                .register(meters).increment(output.promptTokens());
        Counter.builder("agent.evaluation.candidate.tokens").tag("candidate", candidate).tag("direction", "output")
                .register(meters).increment(output.completionTokens());
    }

    /** 把可能带异常类名的 fallbackReason 收敛为低基数标签。 */
    private String metricTag(String reason) {
        if (reason.startsWith("JUDGE_RETRY_EXHAUSTED")) return "judge_retry_exhausted";
        return switch (reason) {
            case "SINGLE_CANDIDATE_FALLBACK", "MINIMUM_SUCCESSFUL_CANDIDATES_NOT_MET" -> "single_candidate";
            case "SINGLE_SAFE_CANDIDATE" -> "single_safe_candidate";
            case "LOW_MARGIN_OR_RISK" -> "low_margin_or_risk";
            case "ALL_CANDIDATES_FAILED" -> "all_candidates_failed";
            case "ALL_CANDIDATES_VETOED" -> "all_candidates_vetoed";
            case "QUALITY_BELOW_THRESHOLD" -> "quality_below_threshold";
            case "LOW_CANDIDATE_DIVERSITY" -> "low_candidate_diversity";
            case "CONCURRENCY_LIMIT" -> "concurrency_limit";
            case "TENANT_DAILY_TOKEN_BUDGET" -> "tenant_daily_token_budget";
            default -> "other";
        };
    }

    /** 去除异步执行包装异常，保留真正模型/超时根因。 */
    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)) current = current.getCause();
        return current;
    }

    /** 错误消息限制 500 字符，防止第三方响应或堆栈片段撑大审计行。 */
    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
