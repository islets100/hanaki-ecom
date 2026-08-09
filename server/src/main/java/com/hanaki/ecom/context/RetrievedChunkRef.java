package com.hanaki.ecom.context;

/**
 * RAG 子图写入 Graph State/Checkpoint 的轻量引用。
 *
 * <p>正文只在最终回答模型调用前，按 tenantId + chunkId + version 从可信知识源重新物化。这样
 * Checkpoint 不会保存多篇完整政策，也能在恢复时发现文档被撤销、换租户或版本已经失效。</p>
 */
public record RetrievedChunkRef(String chunkId, String documentVersion, double retrievalScore) {}
