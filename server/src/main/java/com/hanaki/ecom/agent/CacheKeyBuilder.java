package com.hanaki.ecom.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import static com.hanaki.ecom.agent.CachePolicy.CacheScope;

/** 集中生成不可逆、可版本切换且兼容 Redis Cluster hash-tag 的缓存键。 */
public final class CacheKeyBuilder {
    private CacheKeyBuilder() {}

    /**
     * 键格式固定为：
     * ai:cache:v3:&lt;category&gt;:t:&lt;tenantHash&gt;:s:&lt;scopeHash&gt;:{&lt;resourceDigest&gt;}:&lt;versionDigest&gt;
     *
     * <p>任何原始问题、订单号、tenantId、userId 都不会出现在 Redis Key、慢查询或运维截图里。
     * resourceDigest 单独放在大括号中，使同一资源的值键与租约键落到 Redis Cluster 同一槽位。</p>
     */
    public static String build(CachePolicy policy, CacheContext context,
                               String resourceIdentity, String sourceVersion) {
        String tenant = digest(context.tenantId());
        String scope = scopeDigest(policy.scope(), context);
        String resource = digest(policy.category().name() + "|" + nullToEmpty(resourceIdentity));
        String version = versionDigest(policy, context, sourceVersion);
        return "ai:cache:v3:" + policy.category().name().toLowerCase(Locale.ROOT)
                + ":t:" + tenant + ":s:" + scope + ":{" + resource + "}:" + version;
    }

    public static String scopeDigest(CacheScope scope, CacheContext context) {
        String identity = switch (scope) {
            case PUBLIC -> "public";
            case TENANT -> "tenant|" + context.tenantId();
            case USER -> {
                require(context.userId(), "USER 作用域必须包含 userId");
                yield "user|" + context.tenantId() + "|" + context.userId();
            }
            case RUN -> {
                require(context.userId(), "RUN 作用域必须包含 userId");
                require(context.runId(), "RUN 作用域必须包含 runId");
                yield "run|" + context.tenantId() + "|" + context.userId() + "|" + context.runId();
            }
        };
        return digest(identity);
    }

    public static String versionDigest(CachePolicy policy, CacheContext context, String sourceVersion) {
        return digest(String.join("|", policy.policyVersion(), String.valueOf(policy.schemaVersion()),
                nullToEmpty(sourceVersion), context.knowledgeVersion(), context.promptVersion(),
                context.modelVersion(), context.toolSchemaVersion(), permissionDigest(context),
                String.valueOf(context.mutationEpoch())));
    }

    public static String permissionDigest(CacheContext context) {
        return digest("permission|" + context.permissionIdentity());
    }

    public static String digest(String value) {
        return digestBytes(nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
    }

    public static String digestBytes(byte[] value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value == null ? new byte[0] : value);
            StringBuilder hex = new StringBuilder(64);
            for (byte item : bytes) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法生成 SHA-256 缓存摘要", error);
        }
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
