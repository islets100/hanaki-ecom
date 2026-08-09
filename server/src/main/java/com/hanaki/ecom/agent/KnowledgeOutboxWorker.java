package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.observability.AgentTelemetryService;
import com.hanaki.ecom.store.EcommerceStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 事务 Outbox 消费器。
 *
 * <p>“认领、外部处理、提交结果”被拆成三个阶段：远程 Embedding 或缓存广播不会占住认领事务；
 * PROCESSING 使用租约恢复，进程崩溃后事件不会永久卡死；状态更新还绑定 workerId，避免多实例互相覆盖。</p>
 */
@Component
public class KnowledgeOutboxWorker {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final MemoryContextService memory;
    private final VersionedAgentCache cache;
    private final EcommerceStore store;
    private final ElasticsearchKnowledgeIndex knowledgeIndex;
    private final AgentTelemetryService telemetry;
    private final MeterRegistry meters;
    private final int batchSize;
    private final int maxAttempts;
    private final int leaseSeconds;
    private final String workerId = "outbox-" + UUID.randomUUID().toString().substring(0, 8);
    private final TransactionTemplate transactions;

    @Autowired
    public KnowledgeOutboxWorker(JdbcClient db, ObjectMapper json, MemoryContextService memory,
                                 VersionedAgentCache cache, AgentTelemetryService telemetry,
                                 EcommerceStore store, ElasticsearchKnowledgeIndex knowledgeIndex,
                                 @Value("${agent.knowledge.outbox.batch-size:20}") int batchSize,
                                 @Value("${agent.knowledge.outbox.max-attempts:5}") int maxAttempts,
                                 @Value("${agent.knowledge.outbox.lease-seconds:60}") int leaseSeconds,
                                 MeterRegistry meters, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.json = json;
        this.memory = memory;
        this.cache = cache;
        this.store = store;
        this.knowledgeIndex = knowledgeIndex;
        this.telemetry = telemetry;
        this.meters = meters;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseSeconds = Math.max(10, leaseSeconds);
        this.transactions = new TransactionTemplate(transactionManager);
        Gauge.builder("agent.outbox.backlog", db, client -> client.sql(
                        "select count(*) from outbox_event where status in ('PENDING','RETRY','PROCESSING')")
                .query(Long.class).single().doubleValue()).register(meters);
    }

    /**
     * 保留精确的包内构造器供无需启动完整 Spring 容器的 Outbox 单元测试使用。生产 Bean 始终走
     * 上面的 @Autowired 构造器并获得真实知识源/索引；测试构造器把二者设为空，只测试租约、重试、
     * 死信和候选状态迁移，不会意外发起 Elasticsearch 网络调用。
     */
    KnowledgeOutboxWorker(JdbcClient db, ObjectMapper json, MemoryContextService memory,
                          VersionedAgentCache cache, AgentTelemetryService telemetry,
                          int batchSize, int maxAttempts, int leaseSeconds,
                          MeterRegistry meters, PlatformTransactionManager transactionManager) {
        this(db, json, memory, cache, telemetry, null, null, batchSize, maxAttempts, leaseSeconds,
                meters, transactionManager);
    }

    @Scheduled(fixedDelayString = "${agent.knowledge.outbox.poll-millis:1500}")
    public void poll() {
        recoverExpiredLeases();
        List<Event> events = db.sql("select id,tenant_id,event_type,aggregate_id,payload_json,attempt_count " +
                        "from outbox_event where status in ('PENDING','RETRY') " +
                        "and (next_attempt_at is null or next_attempt_at<=current_timestamp) " +
                        "order by created_at limit :limit")
                .param("limit", batchSize).query((rs, row) -> new Event(rs.getString("id"),
                        rs.getString("tenant_id"), rs.getString("event_type"), rs.getString("aggregate_id"),
                        rs.getString("payload_json"), rs.getInt("attempt_count"))).list();
        for (Event event : events) {
            boolean claimed = Boolean.TRUE.equals(transactions.execute(ignored -> claim(event.id())));
            if (!claimed) continue;
            Timer.Sample sample = Timer.start(meters);
            try {
                Map<String, Object> payload = payload(event.payloadJson());
                String traceId = String.valueOf(payload.getOrDefault("sourceTraceId", ""));
                telemetry.observeLinkedTrace(traceId, "outbox." + metricTag(event.type()), "OUTBOX",
                        Map.of("eventId", event.id(), "eventType", event.type(), "attempt", event.attemptCount() + 1),
                        Map.of("worker", "leased", "delivery", "at-least-once"), () -> {
                            dispatch(event);
                            return Map.of("published", true);
                        });
                transactions.executeWithoutResult(ignored -> markPublished(event.id()));
                Counter.builder("agent.knowledge.outbox").tag("result", "published")
                        .tag("event", metricTag(event.type())).register(meters).increment();
            } catch (RuntimeException error) {
                transactions.executeWithoutResult(ignored -> markFailure(event, error));
            } finally {
                sample.stop(Timer.builder("agent.outbox.processing.duration")
                        .tag("event", metricTag(event.type())).register(meters));
            }
        }
    }

    private boolean claim(String id) {
        return db.sql("update outbox_event set status='PROCESSING',attempt_count=attempt_count+1," +
                        "claimed_at=current_timestamp,worker_id=:worker where id=:id and status in ('PENDING','RETRY')")
                .param("worker", workerId).param("id", id).update() == 1;
    }

    private void dispatch(Event event) {
        switch (event.type()) {
            case "KnowledgeCandidateCreated" -> db.sql(
                            "update knowledge_candidate set status='READY_FOR_REVIEW' where id=:id and status='PENDING_REVIEW'")
                    .param("id", event.aggregateId()).update();
            case "KnowledgeCandidateActivated" -> publishActivatedKnowledge(event.tenantId());
            case "MemoryCandidateApproved" -> memory.activateApprovedCandidate(event.aggregateId());
            case "MemoryProfileProjectionChanged" -> memory.activateProfileProjection(event.payloadJson());
            case "MemoryProfileProjectionDeleted" -> memory.deleteProfileProjection(event.payloadJson());
            default -> throw new IllegalArgumentException("不支持的 Outbox 事件类型: " + event.type());
        }
    }

    /**
     * 激活事务已经先把数据库变成权威事实，Outbox 在事务外执行索引和缓存副作用：先同步当前租户
     * 的完整有效知识集合，再广播缓存失效。若 Elasticsearch 同步失败，本方法抛错，事件进入指数
     * 退避重试；在索引成功前不会错误标记 PUBLISHED。禁用 Elasticsearch 时只做缓存失效。
     */
    private void publishActivatedKnowledge(String tenantId) {
        if (knowledgeIndex != null && store != null && knowledgeIndex.enabled())
            knowledgeIndex.prepareTenant(tenantId, store.activeKnowledge(tenantId));
        cache.invalidateTenant(tenantId);
    }

    private void markPublished(String id) {
        int changed = db.sql("update outbox_event set status='PUBLISHED',published_at=current_timestamp," +
                        "last_error=null,claimed_at=null,worker_id=null where id=:id and status='PROCESSING' and worker_id=:worker")
                .param("id", id).param("worker", workerId).update();
        if (changed != 1) throw new IllegalStateException("Outbox 发布结果失去租约");
    }

    private void markFailure(Event event, RuntimeException error) {
        int attempt = event.attemptCount() + 1;
        boolean exhausted = attempt >= maxAttempts;
        long delaySeconds = Math.min(300L, 1L << Math.min(8, attempt));
        db.sql("update outbox_event set status=:status,last_error=:error,next_attempt_at=:next," +
                        "claimed_at=null,worker_id=null where id=:id and status='PROCESSING' and worker_id=:worker")
                .param("status", exhausted ? "DEAD_LETTER" : "RETRY")
                .param("error", truncate(error.getMessage() == null ? error.getClass().getName() : error.getMessage(), 2000))
                .param("next", Timestamp.from(Instant.now().plusSeconds(delaySeconds)))
                .param("id", event.id()).param("worker", workerId).update();
        Counter.builder("agent.knowledge.outbox").tag("result", exhausted ? "dead_letter" : "retry")
                .tag("event", metricTag(event.type())).register(meters).increment();
    }

    private void recoverExpiredLeases() {
        Instant expired = Instant.now().minus(Duration.ofSeconds(leaseSeconds));
        int recovered = db.sql("update outbox_event set status='RETRY',worker_id=null,claimed_at=null," +
                        "next_attempt_at=current_timestamp,last_error='PROCESSING lease expired; recovered' " +
                        "where status='PROCESSING' and claimed_at<:expired")
                .param("expired", Timestamp.from(expired)).update();
        if (recovered > 0) Counter.builder("agent.knowledge.outbox").tag("result", "lease_recovered")
                .tag("event", "all").register(meters).increment(recovered);
    }

    private Map<String, Object> payload(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception error) { throw new IllegalArgumentException("Outbox payload JSON 无效", error); }
    }

    private String metricTag(String value) {
        return value == null ? "unknown" : value.replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private record Event(String id, String tenantId, String type, String aggregateId,
                         String payloadJson, int attemptCount) {}
}
