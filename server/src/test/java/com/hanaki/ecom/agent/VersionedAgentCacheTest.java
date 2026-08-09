package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionedAgentCacheTest {
    private static final TypeReference<String> STRING = new TypeReference<>() {};

    @Test
    void concurrentMissesShareOneLoaderAndAlwaysRemoveFuture() throws Exception {
        RedisRespClient redis = disabledRedis();
        VersionedAgentCache cache = cache(redis, true);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Optional<String>>> futures = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(2, TimeUnit.SECONDS);
                    return cache.getOrLoad(CachePolicy.retrieval(), context("role-a"),
                            "same-query", "kb-v1", STRING, () -> {
                                loads.incrementAndGet();
                                entered.countDown();
                                try { release.await(2, TimeUnit.SECONDS); }
                                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                                return CacheLoadResult.success("complete-result", "evidence-v1");
                            });
                }));
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            // 给其余 11 个任务足够时间加入首个 Future；加载器在 release 前始终保持阻塞。
            Thread.sleep(100);
            release.countDown();
            for (var future : futures) assertThat(future.get(2, TimeUnit.SECONDS)).contains("complete-result");
        }

        assertThat(loads).hasValue(1);
        assertThat(cache.inFlightSizeForTest()).isZero();
    }

    @Test
    void permissionAndSourceVersionCannotReuseAnotherEnvelope() {
        VersionedAgentCache cache = cache(disabledRedis(), true);
        AtomicInteger loads = new AtomicInteger();

        assertThat(load(cache, context("role-a"), "kb-v1", loads)).contains("value-1");
        assertThat(load(cache, context("role-a"), "kb-v1", loads)).contains("value-1");
        assertThat(load(cache, context("role-b"), "kb-v1", loads)).contains("value-2");
        assertThat(load(cache, context("role-a"), "kb-v2", loads)).contains("value-3");
        assertThat(loads).hasValue(3);
    }

    @Test
    void sourceFailureIsNeverConvertedToNegativeCacheAndFutureIsCleaned() {
        VersionedAgentCache cache = cache(disabledRedis(), true);
        AtomicInteger attempts = new AtomicInteger();

        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> cache.getOrLoad(CachePolicy.retrieval(), context("role-a"),
                    "failed-query", "kb-v1", STRING, () -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("upstream timeout");
                    })).isInstanceOf(IllegalStateException.class).hasMessageContaining("timeout");
            assertThat(cache.inFlightSizeForTest()).isZero();
        }
        assertThat(attempts).hasValue(2);
    }

    @Test
    void onlyExplicitNotFoundCreatesNegativeCacheAndTruncatedResultIsNotCached() {
        VersionedAgentCache cache = cache(disabledRedis(), true);
        CacheContext run = new CacheContext("tenant-a", "user-a", "conversation-a", "run-a",
                "IN_SALE", "tool", "order:read", "orders-v1", "prompt-v1", "model-v1",
                "tools-v1", 0L, "trace");
        AtomicInteger notFoundLoads = new AtomicInteger();
        for (int index = 0; index < 2; index++) {
            assertThat(cache.getOrLoad(CachePolicy.runScopedTool(), run, "missing-order", "orders-v1", STRING,
                    () -> {
                        notFoundLoads.incrementAndGet();
                        return CacheLoadResult.notFound();
                    })).isEmpty();
        }
        assertThat(notFoundLoads).hasValue(1);

        AtomicInteger partialLoads = new AtomicInteger();
        for (int index = 0; index < 2; index++) {
            assertThat(cache.getOrLoad(CachePolicy.runScopedTool(), run, "partial-orders", "orders-v1", STRING,
                    () -> {
                        partialLoads.incrementAndGet();
                        return new CacheLoadResult<>("partial", CacheLoadResult.Status.SUCCESS, "", true);
                    })).contains("partial");
        }
        assertThat(partialLoads).hasValue(2);
    }

    @Test
    void corruptRedisValueAndRedisFailureBothSafelyFallBackToSource() {
        RedisRespClient corrupt = mock(RedisRespClient.class);
        when(corrupt.enabled()).thenReturn(true);
        when(corrupt.get(any())).thenReturn(Optional.of("not-an-envelope"), Optional.empty());
        when(corrupt.tryAcquireLease(any(), any(), any())).thenReturn(true);
        when(corrupt.releaseLease(any(), any())).thenReturn(true);
        AtomicInteger corruptLoads = new AtomicInteger();
        Optional<String> repaired = load(cache(corrupt, true), context("role-a"), "kb-v1", corruptLoads);
        assertThat(repaired).contains("value-1");

        RedisRespClient unavailable = mock(RedisRespClient.class);
        when(unavailable.enabled()).thenReturn(true);
        when(unavailable.get(any())).thenThrow(new RedisRespClient.CacheBackendException("down", null));
        when(unavailable.tryAcquireLease(any(), any(), any()))
                .thenThrow(new RedisRespClient.CacheBackendException("down", null));
        doThrow(new RedisRespClient.CacheBackendException("down", null))
                .when(unavailable).setPx(any(), any(), any());
        AtomicInteger fallbackLoads = new AtomicInteger();
        assertThat(load(cache(unavailable, true), context("role-a"), "kb-v1", fallbackLoads))
                .contains("value-1");
        assertThat(fallbackLoads).hasValue(1);
    }

    private Optional<String> load(VersionedAgentCache cache, CacheContext context,
                                  String sourceVersion, AtomicInteger loads) {
        return cache.getOrLoad(CachePolicy.retrieval(), context, "same-query", sourceVersion, STRING,
                () -> CacheLoadResult.success("value-" + loads.incrementAndGet(), "evidence"));
    }

    private CacheContext context(String permission) {
        return CacheContext.tenant("tenant-a", permission, "kb-v1", "embedding-v3", "trace");
    }

    private RedisRespClient disabledRedis() {
        RedisRespClient redis = mock(RedisRespClient.class);
        when(redis.enabled()).thenReturn(false);
        return redis;
    }

    private VersionedAgentCache cache(RedisRespClient redis, boolean writes) {
        return new VersionedAgentCache(new ObjectMapper().findAndRegisterModules(), redis,
                new SimpleMeterRegistry(), true, true, true, writes, false, true, 8 * 1_024 * 1_024L);
    }
}
