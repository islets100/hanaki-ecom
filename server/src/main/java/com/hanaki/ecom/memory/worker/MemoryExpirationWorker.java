package com.hanaki.ecom.memory.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Memory 生命周期状态迁移任务。
 *
 * <p>任务只做 ACTIVE -> RETRIEVAL_DISABLED/EXPIRED，不直接物理删除。这样“允许进入 Prompt 的
 * 期限”“允许检索的期限”和“审计存储期限”彼此独立；后续归档/删除流程可以按租户合规策略处理
 * storage_retain_until，而不会让已过期内容再次被 Agent 召回。</p>
 */
@Component
public class MemoryExpirationWorker {
    private final JdbcClient db;
    private final Counter expiredEpisodes;
    private final Counter expiredProfiles;
    private final Counter expiredConversationTasks;
    private final Counter expiredCheckpoints;

    public MemoryExpirationWorker(JdbcClient db, MeterRegistry meters) {
        this.db = db;
        // 指标只使用固定的 memoryLayer 标签；tenant/user/memoryId 等高基数字段不进入 Prometheus。
        this.expiredEpisodes = meters.counter("memory.expiration.total", "memoryLayer", "EPISODIC");
        this.expiredProfiles = meters.counter("memory.expiration.total", "memoryLayer", "PROFILE");
        this.expiredConversationTasks = meters.counter("memory.expiration.total", "memoryLayer", "TASK_INDEX");
        this.expiredCheckpoints = meters.counter("memory.expiration.total", "memoryLayer", "CHECKPOINT");
    }

    @Scheduled(fixedDelayString = "${agent.memory.expiration.scan-millis:60000}")
    @Transactional
    public void expire() {
        int episodes = db.sql("update episodic_memory set status='RETRIEVAL_DISABLED',updated_at=current_timestamp " +
                        "where status='ACTIVE' and coalesce(retrieval_expires_at,expires_at)<=current_timestamp")
                .update();
        int profiles = db.sql("update user_profile_fact set status='EXPIRED',updated_at=current_timestamp " +
                        "where coalesce(status,'CONFIRMED')='CONFIRMED' and expires_at is not null " +
                        "and expires_at<=current_timestamp").update();
        // 任务索引是会话层的可丢弃定位信息；过期后保留行供审计，但把状态改为 EXPIRED，且正常
        // 读取还会使用 expires_at 过滤。双重门禁可避免数据库时钟边界或旧客户端漏掉状态条件。
        int conversationTasks = db.sql("update conversation_task_index set task_status='EXPIRED'," +
                        "updated_at=current_timestamp where task_status<>'EXPIRED' and expires_at is not null " +
                        "and expires_at<=current_timestamp").update();
        int checkpoints = db.sql("update agent_checkpoint set status='EXPIRED' where status='ACTIVE' " +
                        "and expires_at is not null and expires_at<=current_timestamp").update();
        if (episodes > 0) expiredEpisodes.increment(episodes);
        if (profiles > 0) expiredProfiles.increment(profiles);
        if (conversationTasks > 0) expiredConversationTasks.increment(conversationTasks);
        if (checkpoints > 0) expiredCheckpoints.increment(checkpoints);
    }
}
