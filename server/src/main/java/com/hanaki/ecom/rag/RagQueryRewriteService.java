package com.hanaki.ecom.rag;

import com.hanaki.ecom.agent.QueryNormalizer;
import com.hanaki.ecom.rag.RagPipelineModels.QueryRewriteResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把模型返回的自由文本改写收敛为可校验的 Query Rewrite 协议。
 *
 * <p>现有模型网关仍可以只返回一段改写文本，但它不能直接控制检索。该服务会重新绑定可信实体、
 * 生成 BM25 与向量检索各自需要的查询、拆分子问题，并对指代不明作澄清判定。这样即使模型遗漏
 * SKU、订单号或日期，下游检索也不会静默丢失这些决定商品/政策范围的关键条件。</p>
 */
@Service
public final class RagQueryRewriteService {
    private static final int MAX_QUERY_CHARS = 4_000;
    private static final int MAX_SUB_QUESTIONS = 4;
    private static final Pattern AMBIGUOUS_REFERENCE = Pattern.compile("(这个|这款|那个|那款|它|该商品|该产品)");
    private static final Pattern CONCRETE_PRODUCT = Pattern.compile(
            "(?i)(SKU[-_:： ]?[A-Z0-9]{3,}|[A-Z]{1,5}[-_][A-Z0-9-]{2,}|商品(?:编号|ID)[:： ]?[A-Z0-9-]{3,})");
    private static final Set<String> PROTECTED_ENTITY_KEYS = Set.of(
            "sku", "skuId", "productId", "spuId", "orderId", "shopId", "date", "purchaseDate",
            "deliveryDate", "amount", "region", "province", "city", "category", "brand", "model");

    private final String rewriteVersion;

    public RagQueryRewriteService(
            @Value("${agent.rag.rewrite.version:structured-rewrite-v1}") String rewriteVersion) {
        this.rewriteVersion = rewriteVersion;
    }

    /**
     * @param originalQuestion 用户本轮原始问题，是模型异常时的唯一安全回退来源
     * @param modelRewrite     模型建议的改写；空、超长或不可用时会回退原问题
     * @param recentMessages   仅用于判断代词是否能由最近会话可靠消解，不会拼进检索条件
     * @param trustedEntities  上游实体节点从认证会话/业务数据抽取的实体，优先级高于模型文本
     * @param modelVersion     真实模型版本，写入审计状态以便离线复现
     * @param modelFailed      模型调用是否已经失败；失败时降低置信度并禁止缓存为正常结果
     */
    public QueryRewriteResult build(String originalQuestion,
                                    String modelRewrite,
                                    List<String> recentMessages,
                                    Map<String, String> trustedEntities,
                                    String modelVersion,
                                    boolean modelFailed) {
        String original = bounded(originalQuestion);
        String candidate = bounded(modelRewrite);
        boolean fallback = modelFailed || candidate.isBlank();
        String standalone = fallback ? original : candidate;
        if (standalone.isBlank()) standalone = "请说明需要查询的商品或售后政策";

        Map<String, String> protectedEntities = protect(trustedEntities);
        String lexical = appendMissingEntities(standalone, protectedEntities);
        lexical = appendLexicalSynonyms(lexical);
        String semantic = appendMissingEntities(standalone, protectedEntities);

        boolean ambiguous = AMBIGUOUS_REFERENCE.matcher(original).find()
                && AMBIGUOUS_REFERENCE.matcher(standalone).find()
                && !hasProductEntity(protectedEntities)
                && !CONCRETE_PRODUCT.matcher(original).find()
                && !recentContextContainsConcreteProduct(recentMessages);
        String reason = ambiguous ? "问题中的商品指代无法由可信实体或最近会话唯一确定" : "";
        double confidence = ambiguous ? 0.30d : (fallback ? 0.55d : 0.88d);
        String version = (modelVersion == null || modelVersion.isBlank() ? "unknown-model" : modelVersion)
                + "|" + rewriteVersion;

        return new QueryRewriteResult(standalone, lexical, semantic, protectedEntities,
                splitSubQuestions(standalone), confidence, ambiguous, reason, version, fallback);
    }

    /**
     * 只接受预先声明的业务实体键，并限制长度。未知键可能是模型伪造的 tenant/index/filter 等控制字段，
     * 因而不能进入检索计划；值为空或异常超长也会被丢弃，避免放大查询和日志。
     */
    private Map<String, String> protect(Map<String, String> entities) {
        if (entities == null || entities.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entities.entrySet()) {
            if (!PROTECTED_ENTITY_KEYS.contains(entry.getKey())) continue;
            String value = bounded(entry.getValue());
            if (!value.isBlank()) result.put(entry.getKey(), value);
        }
        return Map.copyOf(result);
    }

    private String appendMissingEntities(String query, Map<String, String> entities) {
        StringBuilder result = new StringBuilder(query);
        String normalized = QueryNormalizer.normalize(query);
        for (String value : entities.values()) {
            if (!normalized.contains(QueryNormalizer.normalize(value))) result.append(' ').append(value);
        }
        return bounded(result.toString());
    }

    /**
     * BM25 查询使用少量、确定性的领域同义词扩展；向量查询不做此扩展，以免“退货/换货/维修”
     * 被语义模型错误视为同一业务动作。扩展表随代码版本发布，可回放、可测试，也不会接受用户脚本。
     */
    private String appendLexicalSynonyms(String query) {
        LinkedHashSet<String> additions = new LinkedHashSet<>();
        if (containsAny(query, "退货", "退款")) additions.add("退换货");
        if (containsAny(query, "维修", "修理")) additions.add("保修 售后维修");
        if (containsAny(query, "补偿", "赔偿")) additions.add("补偿规则 赔付");
        if (containsAny(query, "参数", "规格")) additions.add("商品参数 规格型号");
        return bounded(additions.isEmpty() ? query : query + " " + String.join(" ", additions));
    }

    private List<String> splitSubQuestions(String question) {
        String[] parts = question.split("(?:[?？；;]|以及|并且|同时|另外|还有)");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String value = part.trim();
            if (value.length() < 2 || result.contains(value)) continue;
            result.add(value);
            if (result.size() >= MAX_SUB_QUESTIONS) break;
        }
        if (result.isEmpty()) result.add(question);
        return List.copyOf(result);
    }

    private boolean hasProductEntity(Map<String, String> entities) {
        return entities.keySet().stream().anyMatch(key ->
                key.equals("sku") || key.equals("skuId") || key.equals("productId")
                        || key.equals("spuId") || key.equals("model"));
    }

    private boolean recentContextContainsConcreteProduct(List<String> recentMessages) {
        if (recentMessages == null) return false;
        return recentMessages.stream().filter(value -> value != null)
                .limit(4).anyMatch(value -> CONCRETE_PRODUCT.matcher(value).find());
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private String bounded(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= MAX_QUERY_CHARS ? normalized : normalized.substring(0, MAX_QUERY_CHARS);
    }
}
