package com.hanaki.ecom.agent;

import java.time.Duration;
import java.util.Objects;

/**
 * 一类缓存数据的完整策略，而不是零散在业务代码里的几个 TTL 常量。
 *
 * <p>业务代码只能选择策略并提供可信上下文、资源投影和加载器；是否进入 L1/L2、能否返回
 * 过期数据、负缓存是否合法、单值最大尺寸等决策全部由本对象表达。这样新增缓存点时，不会
 * 因为复制一段 Redis 代码而绕过多租户隔离、权限版本校验或失败结果过滤。</p>
 */
public record CachePolicy(
        String id,
        CacheCategory category,
        CacheScope scope,
        Freshness freshness,
        boolean l1Enabled,
        boolean l2Enabled,
        boolean allowStale,
        boolean allowNegative,
        boolean cachePartial,
        Duration freshTtl,
        Duration staleTtl,
        Duration loadTimeout,
        Duration lockLease,
        int maxPayloadBytes,
        String serializer,
        int schemaVersion,
        String policyVersion) {

    public CachePolicy {
        Objects.requireNonNull(id, "cache policy id");
        Objects.requireNonNull(category, "cache category");
        Objects.requireNonNull(scope, "cache scope");
        Objects.requireNonNull(freshness, "cache freshness");
        Objects.requireNonNull(freshTtl, "fresh ttl");
        Objects.requireNonNull(staleTtl, "stale ttl");
        Objects.requireNonNull(loadTimeout, "load timeout");
        Objects.requireNonNull(lockLease, "lock lease");
        Objects.requireNonNull(serializer, "serializer");
        Objects.requireNonNull(policyVersion, "policy version");
        if (freshTtl.isNegative() || freshTtl.isZero()) throw new IllegalArgumentException("freshTtl 必须大于 0");
        if (staleTtl.isNegative()) throw new IllegalArgumentException("staleTtl 不能为负数");
        if (!allowStale && !staleTtl.isZero()) throw new IllegalArgumentException("禁止 stale 时 staleTtl 必须为 0");
        if (loadTimeout.isNegative() || loadTimeout.isZero()) throw new IllegalArgumentException("loadTimeout 必须大于 0");
        if (lockLease.isNegative() || lockLease.isZero()) throw new IllegalArgumentException("lockLease 必须大于 0");
        if (maxPayloadBytes < 1_024) throw new IllegalArgumentException("maxPayloadBytes 过小");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion 必须从 1 开始");
        // 部分结果会让“命中”看起来像完整事实，当前平台统一禁止；字段保留用于显式表达该契约。
        if (cachePartial) throw new IllegalArgumentException("平台禁止把部分结果写入缓存");
        if (freshness == Freshness.SIDE_EFFECT && (l1Enabled || l2Enabled))
            throw new IllegalArgumentException("有副作用的写操作不能进入结果缓存");
    }

    public Duration totalTtl() {
        return freshTtl.plus(staleTtl);
    }

    /** 改写后的查询向量是精确哈希命中；模型或归一化版本变化会直接进入新命名空间。 */
    public static CachePolicy embedding() {
        return new CachePolicy("embedding-query", CacheCategory.EMBEDDING, CacheScope.TENANT,
                Freshness.VERSIONED, true, true, false, false, false,
                Duration.ofHours(12), Duration.ZERO, Duration.ofSeconds(8), Duration.ofSeconds(10),
                128 * 1_024, "json-base64-f32", 1, "embedding-policy-v3");
    }

    /**
     * 检索排名允许一个很短的 stale 窗口，只用于上游临时超时；权限、来源版本或信封校验
     * 不通过时仍然是硬 miss，绝不会因为 allowStale 而放宽隔离条件。
     */
    public static CachePolicy retrieval() {
        return new CachePolicy("rag-ranking", CacheCategory.RETRIEVAL, CacheScope.TENANT,
                Freshness.VERSIONED, true, true, true, false, false,
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofSeconds(12), Duration.ofSeconds(15),
                128 * 1_024, "json", 1, "retrieval-policy-v3");
    }

    /** 用户订单、物流等只读工具只允许在同一用户的一次运行内复用，不允许跨运行返回 stale。 */
    public static CachePolicy runScopedTool() {
        return new CachePolicy("tool-read-run", CacheCategory.TOOL_RESULT, CacheScope.RUN,
                Freshness.REALTIME, true, false, false, true, false,
                Duration.ofSeconds(20), Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5),
                64 * 1_024, "json-projected", 1, "tool-policy-v3");
    }

    /** 确定性 Graph 读节点可在同一用户的短时间重复请求间复用；不缓存高温生成或写节点。 */
    public static CachePolicy userScopedNode() {
        return new CachePolicy("graph-node-read", CacheCategory.NODE_RESULT, CacheScope.USER,
                Freshness.VERSIONED, true, true, false, false, false,
                Duration.ofMinutes(3), Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(12),
                64 * 1_024, "json", 1, "node-policy-v3");
    }

    public enum CacheCategory {
        MESSAGE_IDEMPOTENCY, EMBEDDING, RETRIEVAL, CHUNK, TOOL_RESULT, NODE_RESULT, FINAL_ANSWER
    }

    public enum CacheScope { RUN, USER, TENANT, PUBLIC }

    public enum Freshness { IMMUTABLE, VERSIONED, BOUNDED_STALE, REALTIME, SIDE_EFFECT }
}
