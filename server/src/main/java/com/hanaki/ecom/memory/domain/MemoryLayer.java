package com.hanaki.ecom.memory.domain;

/** 四层 Memory 的稳定分类；枚举顺序不代表 Prompt 优先级。 */
public enum MemoryLayer {
    /** 当前 Run/SubRun 的小状态；正式运行时由 Graph State 承载。 */
    WORKING,
    /** 当前 Conversation 的最近原文、滚动摘要和任务索引。 */
    CONVERSATION,
    /** 跨会话的历史事件；只能补充背景，不能替代实时业务系统。 */
    EPISODIC,
    /** 已确认、字段受控且仍在有效期内的稳定用户画像。 */
    PROFILE
}
