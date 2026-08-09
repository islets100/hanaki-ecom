package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.KnowledgeDoc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 在证据进入 Prompt 前构造小而完整、可审计的 Evidence Pack。 */
public final class EvidencePackBuilder {
    private EvidencePackBuilder() {}

    /**
     * 按检索/重排分数取 Top N，同时按 docId+version 和规范化正文摘要双重去重。这里不对正文做
     * 摘要改写，也不删除“但是、除非、仅当、超过”等条件/例外词，避免压缩后把业务规则的适用
     * 边界抹掉。当前 KnowledgeDoc 没有相邻 chunk 序号，因而不猜测拼接顺序；未来增加 chunk
     * metadata 后可在本类扩展相邻合并，而无需改变 ContextAssembler。
     */
    public static List<KnowledgeDoc> build(List<KnowledgeDoc> source, int maxDocuments) {
        if (source == null || source.isEmpty() || maxDocuments <= 0) return List.of();
        List<KnowledgeDoc> sorted = source.stream()
                .filter(doc -> doc != null && doc.content() != null && !doc.content().isBlank())
                .sorted(Comparator.comparingDouble(KnowledgeDoc::score).reversed())
                .toList();
        Set<String> identities = new HashSet<>();
        Set<String> contents = new HashSet<>();
        List<KnowledgeDoc> result = new ArrayList<>();
        for (KnowledgeDoc doc : sorted) {
            String identity = doc.id() + "|" + doc.version();
            String content = CacheKeyBuilder.digest(QueryNormalizer.normalize(doc.content()));
            if (!identities.add(identity) || !contents.add(content)) continue;
            result.add(doc);
            if (result.size() >= maxDocuments) break;
        }
        return List.copyOf(result);
    }
}
