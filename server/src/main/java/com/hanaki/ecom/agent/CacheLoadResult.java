package com.hanaki.ecom.agent;

import java.util.Objects;

/**
 * 数据源加载器对缓存层作出的显式声明。
 *
 * <p>只有 SUCCESS 才是经过权限校验、字段投影且完整的结果；NOT_FOUND 必须是数据源明确返回的
 * 不存在，绝不能用超时/异常/权限拒绝伪装；DO_NOT_CACHE 用于降级结果或实时结果，本次请求仍可
 * 使用该值，但缓存层不会保存它。</p>
 */
public record CacheLoadResult<T>(T value, Status status, String evidenceIdentity, boolean truncated) {
    public CacheLoadResult {
        Objects.requireNonNull(status, "cache load status");
        evidenceIdentity = evidenceIdentity == null ? "" : evidenceIdentity;
        if (status != Status.NOT_FOUND && value == null)
            throw new IllegalArgumentException(status + " 必须携带本次请求可使用的值");
        if (status == Status.NOT_FOUND && value != null)
            throw new IllegalArgumentException("NOT_FOUND 不能同时携带值");
    }

    public static <T> CacheLoadResult<T> success(T value, String evidenceIdentity) {
        return new CacheLoadResult<>(value, Status.SUCCESS, evidenceIdentity, false);
    }

    public static <T> CacheLoadResult<T> notFound() {
        return new CacheLoadResult<>(null, Status.NOT_FOUND, "", false);
    }

    public static <T> CacheLoadResult<T> doNotCache(T value) {
        return new CacheLoadResult<>(value, Status.DO_NOT_CACHE, "", false);
    }

    public enum Status { SUCCESS, NOT_FOUND, DO_NOT_CACHE }
}
