package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hanaki.ecom.agent.CacheLoadResult.Status.DO_NOT_CACHE;
import static com.hanaki.ecom.agent.CacheLoadResult.Status.NOT_FOUND;
import static com.hanaki.ecom.agent.CachePolicy.CacheCategory;
import static com.hanaki.ecom.agent.CachePolicy.Freshness;

/**
 * 平台统一缓存门面：物理上使用分类隔离的 Caffeine L1 与 Redis L2，逻辑上执行同一套 Key、
 * 信封校验、请求合并、租约协调、降级和指标规则。
 *
 * <p>这不是数据源，缓存也永远不是真相。任何不能通过租户、作用域、权限、来源版本、schema、
 * checksum、尺寸和有效期校验的值都会被视为 miss；业务调用方只负责声明 {@link CachePolicy}
 * 和加载完整结果，不得自行 GET/SET Redis。</p>
 */
@Service
public class VersionedAgentCache {
    private final Map<CacheCategory, Cache<String, String>> l1ByCategory = new EnumMap<>(CacheCategory.class);
    private final ConcurrentHashMap<String, CompletableFuture<FlightValue>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tenantDigestByKey = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final RedisRespClient redis;
    private final MeterRegistry meters;
    private final boolean globalL1Enabled;
    private final boolean globalL2Enabled;
    private final boolean readsEnabled;
    private final boolean writesEnabled;
    private final boolean staleDisabled;
    private final boolean finalAnswerDisabled;

    public VersionedAgentCache(
            ObjectMapper json,
            RedisRespClient redis,
            MeterRegistry meters,
            @Value("${agent.cache.l1.enabled:true}") boolean globalL1Enabled,
            @Value("${agent.cache.l2.enabled:true}") boolean globalL2Enabled,
            @Value("${agent.cache.reads-enabled:true}") boolean readsEnabled,
            @Value("${agent.cache.writes-enabled:true}") boolean writesEnabled,
            @Value("${agent.cache.disable-stale:false}") boolean staleDisabled,
            @Value("${agent.cache.disable-final-answer:true}") boolean finalAnswerDisabled,
            @Value("${agent.cache.l1.maximum-weight-bytes:67108864}") long maximumWeightBytes) {
        this.json = json;
        this.redis = redis;
        this.meters = meters;
        this.globalL1Enabled = globalL1Enabled;
        this.globalL2Enabled = globalL2Enabled;
        this.readsEnabled = readsEnabled;
        this.writesEnabled = writesEnabled;
        this.staleDisabled = staleDisabled;
        this.finalAnswerDisabled = finalAnswerDisabled;

        /*
         * 每一类数据使用独立 Caffeine，防止大量 Chunk/Embedding 把实时工具结果或节点结果全部
         * 挤出。maximumWeight 按 UTF-8 字节近似，而不是 maximumSize：一条 100KB 的证据包与一条
         * 100B 的路由结果不应占用相同配额。硬过期仍以信封 expireAt 为准，这里的 24h 只是避免
         * 极端情况下遗留无法再被访问的条目。
         */
        long categoryBudget = Math.max(1_048_576L, maximumWeightBytes / CacheCategory.values().length);
        for (CacheCategory category : CacheCategory.values()) {
            Cache<String, String> cache = Caffeine.newBuilder()
                    .maximumWeight(categoryBudget)
                    .weigher((String key, String value) -> Math.max(1,
                            key.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length))
                    .expireAfterAccess(Duration.ofHours(24))
                    .recordStats()
                    .build();
            l1ByCategory.put(category, cache);
        }
        redis.onInvalidation(this::applyInvalidation);
    }

    /**
     * 执行完整的 cache-aside 流程：L1 → L2 → 本机 single-flight → Redis 短租约 → 数据源。
     *
     * <p>TypeReference 保留 List&lt;T&gt; 等泛型信息；validator 是命中后的最后一道业务结构校验。
     * loader 只有在两层缓存均无可信新鲜值时执行。返回 Optional.empty 只表示“数据源明确不存在且
     * 策略允许负缓存”，超时、权限拒绝和其它异常会原样抛出，不会被伪装成不存在。</p>
     */
    public <T> Optional<T> getOrLoad(CachePolicy policy,
                                     CacheContext context,
                                     String resourceIdentity,
                                     String sourceVersion,
                                     TypeReference<T> payloadType,
                                     Predicate<T> validator,
                                     Supplier<CacheLoadResult<T>> loader) {
        requireCacheable(policy);
        JavaType javaType = json.getTypeFactory().constructType(payloadType.getType());
        String key = CacheKeyBuilder.build(policy, context, resourceIdentity, sourceVersion);
        String tenantDigest = CacheKeyBuilder.digest(context.tenantId());

        Lookup<T> lookup = readsEnabled
                ? lookup(policy, context, sourceVersion, javaType, validator, key)
                : Lookup.miss();
        if (lookup.state == LookupState.FRESH) return lookup.value;

        /*
         * CompletableFuture 由第一个请求创建，后续同键请求只等待同一个结果，避免单实例缓存击穿。
         * finally 中按“键 + 当前 Future 实例”删除，防止旧请求结束时误删刚开始的新一轮 Future。
         */
        CompletableFuture<FlightValue> mine = new CompletableFuture<>();
        CompletableFuture<FlightValue> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) return awaitLeader(policy, lookup, leader);
        try {
            FlightValue loaded = coordinatedLoad(policy, context, sourceVersion, javaType,
                    validator, key, tenantDigest, lookup, loader);
            mine.complete(loaded);
            return loaded.optional();
        } catch (RuntimeException error) {
            mine.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    public <T> Optional<T> getOrLoad(CachePolicy policy,
                                     CacheContext context,
                                     String resourceIdentity,
                                     String sourceVersion,
                                     TypeReference<T> payloadType,
                                     Supplier<CacheLoadResult<T>> loader) {
        return getOrLoad(policy, context, resourceIdentity, sourceVersion, payloadType,
                value -> value != null, loader);
    }

    /** 显式失效单个已知键；不扫描 Redis，也不使用 KEYS。 */
    public void invalidate(CachePolicy policy, CacheContext context,
                           String resourceIdentity, String sourceVersion) {
        String key = CacheKeyBuilder.build(policy, context, resourceIdentity, sourceVersion);
        invalidateLocal(key);
        if (l2Active(policy)) {
            try {
                redis.delete(key);
                redis.publishInvalidation(key);
            } catch (RuntimeException error) {
                backendError("delete");
            }
        }
    }

    /**
     * 来源版本切换后只清理本机该租户的旧值，并向其它实例广播摘要。Redis 中的旧版本键不扫描、
     * 不批量删除，它们因 versionDigest 已不可命中并会按 TTL 自然回收，避免生产环境 KEYS 风暴。
     */
    public void invalidateTenant(String tenantId) {
        String tenantDigest = CacheKeyBuilder.digest(tenantId);
        invalidateLocalTenant(tenantDigest);
        if (redis.enabled()) {
            try { redis.publishInvalidation("tenant:" + tenantDigest); }
            catch (RuntimeException error) { backendError("publish"); }
        }
    }

    int inFlightSizeForTest() {
        return inFlight.size();
    }

    private <T> Lookup<T> lookup(CachePolicy policy, CacheContext context, String sourceVersion,
                                 JavaType type, Predicate<T> validator, String key) {
        Lookup<T> stale = Lookup.miss();
        if (l1Active(policy)) {
            String encoded = l1ByCategory.get(policy.category()).getIfPresent(key);
            if (encoded != null) {
                Lookup<T> local = decodeAndValidate(policy, context, sourceVersion, type, validator, key, encoded);
                if (local.state == LookupState.FRESH) {
                    access(policy, "l1", "hit");
                    return local;
                }
                if (local.state == LookupState.STALE) stale = local;
            }
        }
        if (l2Active(policy)) {
            try {
                Optional<String> encoded = redis.get(key);
                if (encoded.isPresent()) {
                    Lookup<T> remote = decodeAndValidate(policy, context, sourceVersion, type, validator,
                            key, encoded.get());
                    if (remote.state != LookupState.MISS && l1Active(policy)) {
                        l1ByCategory.get(policy.category()).put(key, encoded.get());
                        // 记录摘要用于知识版本事件的本机定向失效；不在内存元数据里保存原始 tenantId。
                        tenantDigestByKey.put(key, CacheKeyBuilder.digest(context.tenantId()));
                    }
                    if (remote.state == LookupState.FRESH) {
                        access(policy, "l2", "hit");
                        return remote;
                    }
                    if (remote.state == LookupState.STALE
                            && (stale.state == LookupState.MISS || remote.createdAt > stale.createdAt)) stale = remote;
                }
            } catch (RuntimeException error) {
                // Redis 是可选加速层。熔断或网络错误不能阻断 L1 与真实数据源，只记录低基数指标。
                backendError("read");
            }
        }
        access(policy, "all", "miss");
        return stale;
    }

    private <T> FlightValue coordinatedLoad(CachePolicy policy, CacheContext context, String sourceVersion,
                                             JavaType type, Predicate<T> validator, String key,
                                             String tenantDigest, Lookup<T> stale,
                                             Supplier<CacheLoadResult<T>> loader) {
        String leaseToken = UUID.randomUUID().toString();
        boolean leaseOwned = false;
        try {
            if (l2Active(policy)) {
                try {
                    leaseOwned = redis.tryAcquireLease(key + ":lease", leaseToken, policy.lockLease());
                } catch (RuntimeException error) {
                    backendError("lease-acquire");
                }
                if (leaseOwned) {
                    // 获得租约前另一个实例可能已经写完，因此必须二次检查，避免串行重复加载。
                    Lookup<T> second = lookup(policy, context, sourceVersion, type, validator, key);
                    if (second.state == LookupState.FRESH) return FlightValue.from(second.value);
                } else {
                    FlightValue waited = waitForRemoteWriter(policy, context, sourceVersion,
                            type, validator, key, stale);
                    if (waited != null) return waited;
                }
            }

            try {
                CacheLoadResult<T> loaded = loader.get();
                if (loaded == null) throw new IllegalStateException("缓存加载器返回了 null 契约");
                if (loaded.status() == NOT_FOUND) {
                    if (policy.allowNegative()) writeEnvelope(policy, context, sourceVersion, type, key,
                            tenantDigest, null, loaded, true);
                    return FlightValue.empty();
                }
                if (!validator.test(loaded.value()))
                    throw new IllegalStateException("数据源结果未通过缓存前结构校验");

                // 降级结果、被截断结果和显式 DO_NOT_CACHE 只服务当前请求，永远不污染后续命中。
                if (loaded.status() != DO_NOT_CACHE && !loaded.truncated() && writesEnabled)
                    writeEnvelope(policy, context, sourceVersion, type, key, tenantDigest,
                            loaded.value(), loaded, false);
                return FlightValue.of(loaded.value());
            } catch (RuntimeException sourceFailure) {
                if (staleAllowed(policy) && stale.state == LookupState.STALE && stale.value.isPresent()) {
                    access(policy, "stale", "served");
                    return FlightValue.from(stale.value);
                }
                throw sourceFailure;
            }
        } finally {
            if (leaseOwned) {
                try { redis.releaseLease(key + ":lease", leaseToken); }
                catch (RuntimeException error) { backendError("lease-release"); }
            }
        }
    }

    private <T> FlightValue waitForRemoteWriter(CachePolicy policy, CacheContext context, String sourceVersion,
                                                JavaType type, Predicate<T> validator, String key,
                                                Lookup<T> stale) {
        long deadline = System.nanoTime() + Math.min(policy.loadTimeout().toNanos(), Duration.ofMillis(450).toNanos());
        int attempt = 0;
        while (attempt++ < 3 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(35L * attempt);
                Lookup<T> remote = lookup(policy, context, sourceVersion, type, validator, key);
                if (remote.state == LookupState.FRESH) return FlightValue.from(remote.value);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 另一实例仍在加载时，允许 stale 的低风险数据优先快速返回；高风险/实时数据继续查源。
        if (staleAllowed(policy) && stale.state == LookupState.STALE && stale.value.isPresent())
            return FlightValue.from(stale.value);
        return null;
    }

    private <T> Optional<T> awaitLeader(CachePolicy policy, Lookup<T> stale,
                                        CompletableFuture<FlightValue> leader) {
        try {
            return leader.get(policy.loadTimeout().toMillis(), TimeUnit.MILLISECONDS).optional();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (staleAllowed(policy) && stale.value.isPresent()) return stale.value;
            throw new CacheLoadException("等待同键缓存加载时线程被中断", interrupted);
        } catch (TimeoutException timeout) {
            if (staleAllowed(policy) && stale.value.isPresent()) return stale.value;
            throw new CacheLoadException("等待同键缓存加载超时", timeout);
        } catch (ExecutionException failed) {
            if (staleAllowed(policy) && stale.value.isPresent()) return stale.value;
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new CacheLoadException("同键缓存加载失败", cause);
        }
    }

    private <T> void writeEnvelope(CachePolicy policy, CacheContext context, String sourceVersion,
                                   JavaType type, String key, String tenantDigest, T value,
                                   CacheLoadResult<T> loaded, boolean negative) {
        try {
            JsonNode payload = negative ? NullNode.instance : json.valueToTree(value);
            byte[] payloadBytes = negative ? new byte[0] : json.writeValueAsBytes(payload);
            if (payloadBytes.length > policy.maxPayloadBytes()) {
                access(policy, "write", "oversize");
                return;
            }
            long now = System.currentTimeMillis();
            long freshMillis = jitter(policy.freshTtl().toMillis());
            long staleMillis = staleAllowed(policy) ? jitter(policy.staleTtl().toMillis()) : 0L;
            CacheEnvelope envelope = new CacheEnvelope(policy.schemaVersion(), type.toCanonical(), payload,
                    now, now + freshMillis, now + freshMillis + staleMillis,
                    tenantDigest, CacheKeyBuilder.scopeDigest(policy.scope(), context),
                    CacheKeyBuilder.versionDigest(policy, context, sourceVersion), policy.policyVersion(),
                    CacheKeyBuilder.permissionDigest(context),
                    CacheKeyBuilder.digest(loaded.evidenceIdentity()), CacheKeyBuilder.digestBytes(payloadBytes),
                    payloadBytes.length, "none", loaded.truncated(), negative);
            String encoded = json.writeValueAsString(envelope);

            /*
             * 写顺序必须是 L2 后 L1：若先写 L1 而 Redis 写失败，本实例会表现为命中、其它实例却
             * 仍然查源，容易掩盖跨实例不一致。Redis 失败时，仅安全的版本化/不可变数据允许以 L1
             * 作为短路优化；REALTIME 和 SIDE_EFFECT 从不因降级而扩大复用范围。
             */
            boolean l2Written = !l2Active(policy);
            if (l2Active(policy)) {
                try {
                    redis.setPx(key, Duration.ofMillis(Math.max(1L, envelope.expireAtEpochMillis() - now)), encoded);
                    l2Written = true;
                } catch (RuntimeException error) {
                    backendError("write");
                }
            }
            if (l1Active(policy) && (l2Written || safeL1Fallback(policy))) {
                l1ByCategory.get(policy.category()).put(key, encoded);
                tenantDigestByKey.put(key, tenantDigest);
            }
        } catch (Exception error) {
            // 序列化失败属于缓存旁路失败，不应该把一次已成功的数据源调用改成业务失败。
            backendError("encode");
        }
    }

    private <T> Lookup<T> decodeAndValidate(CachePolicy policy, CacheContext context, String sourceVersion,
                                            JavaType type, Predicate<T> validator, String key, String encoded) {
        try {
            CacheEnvelope envelope = json.readValue(encoded, CacheEnvelope.class);
            long now = System.currentTimeMillis();
            boolean metadataMatches = envelope.schemaVersion() == policy.schemaVersion()
                    && type.toCanonical().equals(envelope.payloadType())
                    && CacheKeyBuilder.digest(context.tenantId()).equals(envelope.tenantDigest())
                    && CacheKeyBuilder.scopeDigest(policy.scope(), context).equals(envelope.scopeDigest())
                    && CacheKeyBuilder.permissionDigest(context).equals(envelope.permissionDigest())
                    && CacheKeyBuilder.versionDigest(policy, context, sourceVersion).equals(envelope.sourceVersionDigest())
                    && policy.policyVersion().equals(envelope.policyVersion())
                    && envelope.createdAtEpochMillis() <= now + Duration.ofMinutes(5).toMillis()
                    && envelope.expireAtEpochMillis() >= envelope.freshUntilEpochMillis()
                    && !envelope.truncated();
            if (!metadataMatches || envelope.expireAtEpochMillis() < now) {
                discardInvalid(key);
                return Lookup.miss();
            }
            if (envelope.negative()) {
                if (!policy.allowNegative() || envelope.payloadBytes() != 0) {
                    discardInvalid(key);
                    return Lookup.miss();
                }
                return now <= envelope.freshUntilEpochMillis()
                        ? Lookup.fresh(Optional.empty(), envelope.createdAtEpochMillis()) : Lookup.miss();
            }
            byte[] bytes = json.writeValueAsBytes(envelope.payload());
            if (bytes.length != envelope.payloadBytes() || bytes.length > policy.maxPayloadBytes()
                    || !CacheKeyBuilder.digestBytes(bytes).equals(envelope.checksum())) {
                discardInvalid(key);
                return Lookup.miss();
            }
            T value = json.convertValue(envelope.payload(), type);
            if (value == null || !validator.test(value)) {
                discardInvalid(key);
                return Lookup.miss();
            }
            return now <= envelope.freshUntilEpochMillis()
                    ? Lookup.fresh(Optional.of(value), envelope.createdAtEpochMillis())
                    : Lookup.stale(Optional.of(value), envelope.createdAtEpochMillis());
        } catch (Exception invalid) {
            discardInvalid(key);
            return Lookup.miss();
        }
    }

    private void discardInvalid(String key) {
        invalidateLocal(key);
        if (!redis.enabled()) return;
        /*
         * 删除损坏 L2 值不应增加当前请求尾延迟，因此使用虚拟线程做 best-effort 清理。即使删除失败，
         * 下次读取仍会重复校验并保持 miss，不存在“脏值绕过校验”窗口。
         */
        Thread.ofVirtual().name("cache-invalid-delete").start(() -> {
            try { redis.delete(key); }
            catch (RuntimeException ignored) { backendError("invalid-delete"); }
        });
    }

    private void applyInvalidation(String keyOrTenant) {
        if (keyOrTenant.startsWith("tenant:")) {
            invalidateLocalTenant(keyOrTenant.substring("tenant:".length()));
        } else {
            invalidateLocal(keyOrTenant);
        }
    }

    private void invalidateLocal(String key) {
        l1ByCategory.values().forEach(cache -> cache.invalidate(key));
        tenantDigestByKey.remove(key);
    }

    private void invalidateLocalTenant(String tenantDigest) {
        tenantDigestByKey.entrySet().removeIf(entry -> {
            if (!tenantDigest.equals(entry.getValue())) return false;
            l1ByCategory.values().forEach(cache -> cache.invalidate(entry.getKey()));
            return true;
        });
    }

    private void requireCacheable(CachePolicy policy) {
        if (policy.freshness() == Freshness.SIDE_EFFECT)
            throw new IllegalArgumentException("有副作用操作必须使用业务幂等键，不能使用结果缓存");
        if (policy.category() == CacheCategory.FINAL_ANSWER && finalAnswerDisabled)
            throw new IllegalStateException("最终回答缓存已由安全开关禁用");
    }

    private boolean l1Active(CachePolicy policy) {
        return globalL1Enabled && policy.l1Enabled();
    }

    private boolean l2Active(CachePolicy policy) {
        return globalL2Enabled && policy.l2Enabled() && redis.enabled();
    }

    private boolean staleAllowed(CachePolicy policy) {
        return !staleDisabled && policy.allowStale();
    }

    private boolean safeL1Fallback(CachePolicy policy) {
        return policy.freshness() == Freshness.IMMUTABLE
                || policy.freshness() == Freshness.VERSIONED
                || policy.freshness() == Freshness.BOUNDED_STALE;
    }

    private long jitter(long millis) {
        if (millis <= 0) return 0;
        // ±10% 打散同批写入的集中失效时间，避免整点或发布后形成缓存雪崩。
        return Math.max(1L, Math.round(millis * ThreadLocalRandom.current().nextDouble(0.90d, 1.10d)));
    }

    private void access(CachePolicy policy, String level, String result) {
        meters.counter("agent.cache.access", "category", policy.category().name().toLowerCase(),
                "level", level, "result", result).increment();
    }

    private void backendError(String operation) {
        meters.counter("agent.cache.backend.errors", "backend", "redis", "operation", operation).increment();
    }

    private enum LookupState { MISS, FRESH, STALE }

    private static final class Lookup<T> {
        private final LookupState state;
        private final Optional<T> value;
        private final long createdAt;

        private Lookup(LookupState state, Optional<T> value, long createdAt) {
            this.state = state;
            this.value = value;
            this.createdAt = createdAt;
        }

        static <T> Lookup<T> miss() { return new Lookup<>(LookupState.MISS, Optional.empty(), 0L); }
        static <T> Lookup<T> fresh(Optional<T> value, long createdAt) {
            return new Lookup<>(LookupState.FRESH, value, createdAt);
        }
        static <T> Lookup<T> stale(Optional<T> value, long createdAt) {
            return new Lookup<>(LookupState.STALE, value, createdAt);
        }
    }

    private record FlightValue(Object value, boolean present) {
        static FlightValue empty() { return new FlightValue(null, false); }
        static FlightValue of(Object value) { return new FlightValue(value, true); }
        static FlightValue from(Optional<?> value) { return value.map(FlightValue::of).orElseGet(FlightValue::empty); }
        @SuppressWarnings("unchecked") <T> Optional<T> optional() {
            return present ? Optional.of((T) value) : Optional.empty();
        }
    }

    public static final class CacheLoadException extends RuntimeException {
        CacheLoadException(String message, Throwable cause) { super(message, cause); }
    }
}
