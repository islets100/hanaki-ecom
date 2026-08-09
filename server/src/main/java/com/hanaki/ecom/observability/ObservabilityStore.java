package com.hanaki.ecom.observability;

import com.hanaki.ecom.observability.ObservabilityModels.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 将可回放 Span 持久化到业务数据库，确保即使 Grafana/Langfuse 未启动也能本地验收。 */
@Repository
public class ObservabilityStore {
    private final JdbcClient db;

    public ObservabilityStore(JdbcClient db) { this.db = db; }

    public void save(SpanWrite span) {
        db.sql("""
                insert into agent_trace(
                  id,trace_id,span_id,parent_span_id,tenant_id,user_id,conversation_id,run_id,
                  node_name,span_kind,status,elapsed_ms,result,input_json,output_json,metadata_json,
                  error_type,error_message,model_name,prompt_tokens,completion_tokens,total_tokens,
                  cost_cny,started_at,finished_at,created_at)
                values(:id,:trace,:span,:parent,:tenant,:user,:conversation,:run,:node,:kind,:status,
                  :elapsed,:result,:input,:output,:metadata,:errorType,:errorMessage,:model,
                  :promptTokens,:completionTokens,:totalTokens,:cost,:started,:finished,current_timestamp)
                """)
                .param("id", span.id()).param("trace", span.traceId()).param("span", span.spanId())
                .param("parent", span.parentSpanId()).param("tenant", span.tenantId()).param("user", span.userId())
                .param("conversation", span.conversationId()).param("run", span.runId()).param("node", span.nodeName())
                .param("kind", span.spanKind()).param("status", span.status()).param("elapsed", span.elapsedMs())
                .param("result", span.result()).param("input", span.inputJson()).param("output", span.outputJson())
                .param("metadata", span.metadataJson()).param("errorType", span.errorType())
                .param("errorMessage", span.errorMessage()).param("model", span.modelName())
                .param("promptTokens", span.promptTokens()).param("completionTokens", span.completionTokens())
                .param("totalTokens", span.totalTokens()).param("cost", span.costCny())
                .param("started", Timestamp.from(span.startedAt())).param("finished", Timestamp.from(span.finishedAt()))
                .update();
    }

    /** 为延迟执行的 Outbox/Memory 作业恢复原始根 Span 的最小可信上下文。 */
    public Optional<StoredTraceContext> context(String traceId) {
        return db.sql("""
                select trace_id,span_id,tenant_id,user_id,conversation_id,run_id,started_at
                from agent_trace where trace_id=:trace and node_name='agent.run'
                order by created_at desc limit 1
                """).param("trace", traceId).query((rs, row) -> new StoredTraceContext(
                        rs.getString("trace_id"), rs.getString("span_id"), rs.getString("tenant_id"),
                        rs.getString("user_id"), rs.getString("conversation_id"), rs.getString("run_id"),
                        instant(rs.getTimestamp("started_at")))).optional();
    }

    public List<TraceSummary> summaries(String tenantId, String userId, int limit) {
        return db.sql("""
                select trace_id, max(run_id) run_id, max(conversation_id) conversation_id,
                  coalesce(max(case when node_name='agent.run' then status end),'UNKNOWN') trace_status,
                  coalesce(max(case when node_name='agent.run' then elapsed_ms end),max(elapsed_ms),0) elapsed,
                  count(*) span_count, coalesce(sum(prompt_tokens),0) prompt_tokens,
                  coalesce(sum(completion_tokens),0) completion_tokens,
                  coalesce(sum(total_tokens),0) total_tokens, coalesce(sum(cost_cny),0) cost,
                  min(coalesce(started_at,created_at)) started
                from agent_trace
                where tenant_id=:tenant and user_id=:user and span_id is not null
                group by trace_id order by started desc limit :limit
                """).param("tenant", tenantId).param("user", userId).param("limit", Math.min(100, Math.max(1, limit)))
                .query((rs, n) -> new TraceSummary(rs.getString("trace_id"), rs.getString("run_id"),
                        rs.getString("conversation_id"), rs.getString("trace_status"), rs.getLong("elapsed"),
                        rs.getInt("span_count"), rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"),
                        rs.getInt("total_tokens"), value(rs.getBigDecimal("cost")),
                        rs.getTimestamp("started").toInstant())).list();
    }

    public Optional<TraceReplay> replay(String tenantId, String userId, String traceId) {
        List<TraceSpanView> spans = db.sql("""
                select * from agent_trace where tenant_id=:tenant and user_id=:user
                  and trace_id=:trace and span_id is not null
                order by coalesce(started_at,created_at), created_at
                """).param("tenant", tenantId).param("user", userId).param("trace", traceId)
                .query((rs, n) -> new TraceSpanView(rs.getString("span_id"), rs.getString("parent_span_id"),
                        rs.getString("node_name"), rs.getString("span_kind"), rs.getString("status"),
                        rs.getLong("elapsed_ms"), rs.getString("input_json"), rs.getString("output_json"),
                        rs.getString("metadata_json"), rs.getString("error_type"), rs.getString("error_message"),
                        rs.getString("model_name"), rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"),
                        rs.getInt("total_tokens"), value(rs.getBigDecimal("cost_cny")),
                        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")))).list();
        if (spans.isEmpty()) return Optional.empty();
        TraceSummary summary = summaries(tenantId, userId, 100).stream()
                .filter(item -> item.traceId().equals(traceId)).findFirst()
                .orElse(new TraceSummary(traceId, "", "", "UNKNOWN", 0, spans.size(), 0, 0, 0,
                        BigDecimal.ZERO, spans.getFirst().startedAt()));
        return Optional.of(new TraceReplay(traceId, summary, spans));
    }

    public ObservabilityOverview overview(String tenantId, String userId) {
        List<TraceSummary> traces = summaries(tenantId, userId, 100);
        List<TraceSpanView> spans = traces.stream().flatMap(trace -> replay(tenantId, userId, trace.traceId())
                .stream().flatMap(item -> item.spans().stream())).toList();
        int success = (int) traces.stream().filter(item -> "COMPLETED".equals(item.status())
                || "HANDOFF".equals(item.status()) || "WAITING_CONFIRMATION".equals(item.status())).count();
        int failed = (int) traces.stream().filter(item -> "ERROR".equals(item.status()) || "FAILED".equals(item.status())).count();
        long average = traces.isEmpty() ? 0 : Math.round(traces.stream().mapToLong(TraceSummary::elapsedMs).average().orElse(0));
        return new ObservabilityOverview(traces.size(), success, failed, average,
                countKind(spans, "MODEL"), countKind(spans, "RETRIEVAL"), countKind(spans, "TOOL"),
                traces.stream().mapToLong(TraceSummary::promptTokens).sum(),
                traces.stream().mapToLong(TraceSummary::completionTokens).sum(),
                traces.stream().mapToLong(TraceSummary::totalTokens).sum(),
                traces.stream().map(TraceSummary::costCny).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private int countKind(List<TraceSpanView> spans, String kind) {
        return (int) spans.stream().filter(span -> kind.equals(span.kind())).count();
    }

    private static BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    public record StoredTraceContext(String traceId, String rootSpanId, String tenantId, String userId,
                                     String conversationId, String runId, Instant startedAt) {}
}
