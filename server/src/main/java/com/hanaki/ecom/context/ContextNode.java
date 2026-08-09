package com.hanaki.ecom.context;

/** 所有会调用大模型的节点代码；它也是 ContextPolicy 的查找键。 */
public enum ContextNode {
    INTENT_ROUTE,
    QUERY_REWRITE,
    ANSWER_GENERATION,
    CANDIDATE_JUDGE
}
