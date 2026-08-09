package com.hanaki.ecom.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多候选评审的并发背压闸门。
 *
 * <p>一条普通请求在评审模式下最多放大为三个候选模型调用和一个 Judge 调用，所以不能只依赖
 * Web 线程池。这里依次限制全局批次数、单租户批次数和全局候选数；有界执行器队列再构成第四层
 * 背压。许可全部使用公平 Semaphore，避免高流量租户长期占用可用槽位。</p>
 */
@Component
public final class EvaluationConcurrencyGuard {
    private final Semaphore globalBatches;
    private final Semaphore globalCandidates;
    private final int tenantBatchLimit;
    private final ConcurrentHashMap<String, Semaphore> tenantBatches = new ConcurrentHashMap<>();

    public EvaluationConcurrencyGuard(
            @Value("${agent.evaluation.concurrency.max-global-batches:20}") int maxGlobalBatches,
            @Value("${agent.evaluation.concurrency.max-batches-per-tenant:3}") int maxBatchesPerTenant,
            @Value("${agent.evaluation.concurrency.max-global-candidates:40}") int maxGlobalCandidates) {
        this.globalBatches = new Semaphore(Math.max(1, maxGlobalBatches), true);
        this.globalCandidates = new Semaphore(Math.max(1, maxGlobalCandidates), true);
        this.tenantBatchLimit = Math.max(1, maxBatchesPerTenant);
    }

    public Permit tryBatch(String tenantId) {
        Semaphore tenant = tenantBatches.computeIfAbsent(tenantId, ignored -> new Semaphore(tenantBatchLimit, true));
        // 获取顺序固定为 global → tenant；租户许可失败时必须立即归还 global，防止许可泄漏。
        if (!globalBatches.tryAcquire()) return Permit.denied();
        if (!tenant.tryAcquire()) {
            globalBatches.release();
            return Permit.denied();
        }
        return new Permit(true, () -> {
            tenant.release();
            globalBatches.release();
        });
    }

    public Permit tryCandidate() {
        // 候选许可与批次许可分开，避免多个已获批批次同时展开 3 倍调用击穿模型并发上限。
        if (!globalCandidates.tryAcquire()) return Permit.denied();
        return new Permit(true, globalCandidates::release);
    }

    public static final class Permit implements AutoCloseable {
        private final boolean acquired;
        private final Runnable releaser;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(boolean acquired, Runnable releaser) {
            this.acquired = acquired;
            this.releaser = releaser;
        }

        static Permit denied() { return new Permit(false, () -> {}); }
        public boolean acquired() { return acquired; }
        /** AtomicBoolean 保证 finally、异常恢复或重复 close 都只归还一次 Semaphore。 */
        @Override public void close() {
            if (acquired && closed.compareAndSet(false, true)) releaser.run();
        }
    }
}
