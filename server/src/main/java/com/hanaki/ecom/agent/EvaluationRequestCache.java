package com.hanaki.ecom.agent;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-of-3 生命周期内共享的只读请求缓存。
 *
 * <p>三个候选面对的是同一份冻结事实，它们不应分别查询三次订单或物流。每个 batchId 对应一个
 * ConcurrentHashMap，ScopedCommerceTools 使用 computeIfAbsent 把同键工具调用合并为一次。此缓存
 * 只存在于评审批次内，不跨用户、批次或应用重启；异常不会被 computeIfAbsent 保存，写工具也从未
 * 暴露给候选。批次 finally 必须调用 close，避免高基数 batchId 常驻内存。</p>
 */
@Service
public class EvaluationRequestCache {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> batches = new ConcurrentHashMap<>();

    public ConcurrentHashMap<String, Object> open(String batchId, Map<String, Object> seed) {
        // seed 来自已经冻结的服务端业务事实，用于候选开始前预热，不接受模型输出作为种子。
        ConcurrentHashMap<String, Object> cache = batches.computeIfAbsent(batchId, ignored -> new ConcurrentHashMap<>());
        if (seed != null) cache.putAll(seed);
        return cache;
    }

    public ConcurrentHashMap<String, Object> get(String batchId) {
        // candidate 分支并行进入时，computeIfAbsent 保证它们拿到同一个线程安全容器。
        return batches.computeIfAbsent(batchId, ignored -> new ConcurrentHashMap<>());
    }

    /** 释放整个批次作用域；不逐项清除以减少并发分支结束时的锁竞争。 */
    public void close(String batchId) { batches.remove(batchId); }
}
