package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * L1 和 L2 共用的自描述缓存信封。
 *
 * <p>缓存命中不等于数据可信。读取方必须先核对 schema、payload 类型、租户/作用域、权限摘要、
 * 来源版本、策略版本、尺寸与 checksum，然后才能反序列化 payload。把这些字段随值保存，才能在
 * 权限变化、模型升级、灰度策略切换或脏值注入时把旧值安全地当作 miss，而不是继续返回。</p>
 */
public record CacheEnvelope(
        int schemaVersion,
        String payloadType,
        JsonNode payload,
        long createdAtEpochMillis,
        long freshUntilEpochMillis,
        long expireAtEpochMillis,
        String tenantDigest,
        String scopeDigest,
        String sourceVersionDigest,
        String policyVersion,
        String permissionDigest,
        String evidenceDigest,
        String checksum,
        int payloadBytes,
        String compression,
        boolean truncated,
        boolean negative) {
}
