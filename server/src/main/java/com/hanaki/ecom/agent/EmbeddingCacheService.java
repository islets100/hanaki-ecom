package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;

/** 精确缓存改写后查询的 Embedding；不做相似查询复用。 */
@Service
public final class EmbeddingCacheService {
    private static final String NORMALIZATION_VERSION = "query-nfkc-whitespace-v1";

    private final VersionedAgentCache cache;
    private final EmbeddingModel model;
    private final String modelVersion;
    private final int configuredDimensions;

    public EmbeddingCacheService(
            VersionedAgentCache cache,
            EmbeddingModel model,
            @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v3}") String modelVersion,
            @Value("${spring.ai.dashscope.embedding.options.dimensions:512}") int configuredDimensions) {
        this.cache = cache;
        this.model = model;
        this.modelVersion = modelVersion;
        this.configuredDimensions = configuredDimensions;
    }

    public String modelVersion() {
        return modelVersion;
    }

    /**
     * <p>调用方传入的 query 应当已经是 Graph 的改写结果；本方法仍执行一次幂等归一化，确保全角、
     * 大小写和多余空格不会制造无意义的重复键。Key 同时包含 provider/model/dimension/normalizer，
     * 任一版本变化都会进入新命名空间，不会把旧维度向量误交给新索引。</p>
     *
     * <p>向量使用 little-endian float32 二进制再 Base64，而不是 JSON 浮点数组。这样 L2 值更小、
     * 解析更确定；解码后还会核对维度和字节数，损坏值由统一信封校验路径删除并回源。</p>
     */
    public float[] embedRewrittenQuery(String tenantId, String query, String traceId) {
        String normalized = QueryNormalizer.normalize(query);
        String sourceVersion = "dashscope|" + modelVersion + "|dim=" + configuredDimensions
                + "|normalizer=" + NORMALIZATION_VERSION;
        CacheContext context = CacheContext.tenant(tenantId, "knowledge:read", "", modelVersion, traceId);
        BinaryVector value = cache.getOrLoad(CachePolicy.embedding(), context,
                        "rewritten-query|" + normalized, sourceVersion,
                        new TypeReference<BinaryVector>() {}, this::valid,
                        () -> CacheLoadResult.success(encode(model.embed(normalized)), "exact-query-vector"))
                .orElseThrow(() -> new IllegalStateException("Embedding 数据源没有返回向量"));
        return decode(value);
    }

    private boolean valid(BinaryVector vector) {
        if (vector == null || vector.dimension() <= 0 || vector.base64Float32() == null) return false;
        if (configuredDimensions > 0 && vector.dimension() != configuredDimensions) return false;
        try {
            byte[] bytes = Base64.getDecoder().decode(vector.base64Float32());
            if (bytes.length != vector.dimension() * Float.BYTES) return false;
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining()) if (!Float.isFinite(buffer.getFloat())) return false;
            return true;
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
    }

    private BinaryVector encode(float[] vector) {
        if (vector == null || vector.length == 0) throw new ModelCallException("EmbeddingModel 返回空向量");
        if (configuredDimensions > 0 && vector.length != configuredDimensions)
            throw new ModelCallException("EmbeddingModel 返回维度 " + vector.length
                    + "，与配置维度 " + configuredDimensions + " 不一致");
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float item : vector) {
            // NaN/Infinity 会污染 cosine、kNN 和缓存命中结果，必须在进入任何缓存或索引前拒绝。
            if (!Float.isFinite(item)) throw new ModelCallException("EmbeddingModel 返回包含 NaN 或 Infinity 的非法向量");
            buffer.putFloat(item);
        }
        return new BinaryVector(vector.length, Base64.getEncoder().encodeToString(buffer.array()),
                NORMALIZATION_VERSION, modelVersion);
    }

    private float[] decode(BinaryVector vector) {
        byte[] bytes = Base64.getDecoder().decode(vector.base64Float32());
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[vector.dimension()];
        for (int index = 0; index < result.length; index++) result[index] = buffer.getFloat();
        return result;
    }

    /** 仅供知识索引批量生成文档向量；正文不进入跨实例查询缓存。 */
    List<float[]> embedDocumentsForIndex(List<String> texts) {
        return model.embed(texts);
    }

    public record BinaryVector(int dimension, String base64Float32,
                               String normalizationVersion, String modelVersion) {}
}
