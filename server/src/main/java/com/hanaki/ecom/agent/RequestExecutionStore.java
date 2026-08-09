package com.hanaki.ecom.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.ChatRequest;
import com.hanaki.ecom.domain.Domain.ChatResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;

/** 持久化消息幂等仓库，避免重启后相同 messageId 再次产生写操作和模型成本。 */
@Repository
public class RequestExecutionStore {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final int leaseSeconds;
    private final int duplicateWaitSeconds;
    private final MeterRegistry meters;

    @Autowired
    public RequestExecutionStore(JdbcClient db, ObjectMapper json,
                                 @Value("${agent.request-dedup.lease-seconds:300}") int leaseSeconds,
                                 @Value("${agent.request-dedup.duplicate-wait-seconds:35}") int duplicateWaitSeconds,
                                 MeterRegistry meters) {
        this.db = db;
        this.json = json;
        this.leaseSeconds = Math.max(30, leaseSeconds);
        this.duplicateWaitSeconds = Math.max(1, duplicateWaitSeconds);
        this.meters = meters;
    }

    /** 不启动 Spring 容器的仓库测试使用。 */
    RequestExecutionStore(JdbcClient db, ObjectMapper json, int leaseSeconds, int duplicateWaitSeconds) {
        this.db = db; this.json = json; this.leaseSeconds = Math.max(30, leaseSeconds);
        this.duplicateWaitSeconds = Math.max(1, duplicateWaitSeconds); this.meters = null;
    }

    public ExecutionIdentity acquire(ChatRequest request, String proposedTraceId) {
        String hash = sha256(request.content());
        Optional<ExecutionIdentity> existing = find(request);
        if (existing.isPresent()) return acquireExisting(request, validate(existing.get(), hash));
        String leaseOwner = UUID.randomUUID().toString();
        Instant leaseExpiry = Instant.now().plusSeconds(leaseSeconds);
        ExecutionIdentity created = new ExecutionIdentity(
                "run-" + UUID.randomUUID(), "sub-" + UUID.randomUUID(), proposedTraceId,
                "RUNNING", hash, null, true, leaseOwner, leaseExpiry);
        try {
            db.sql("insert into agent_request_dedup(tenant_id,conversation_id,message_id,user_id,request_hash,run_id,sub_run_id,trace_id,status,lease_owner,lease_expires_at,created_at,updated_at) " +
                            "values(:tenant,:conversation,:message,:user,:hash,:run,:sub,:trace,'RUNNING',:owner,:expiry,current_timestamp,current_timestamp)")
                    .param("tenant", request.tenantId()).param("conversation", request.conversationId())
                    .param("message", request.messageId()).param("user", request.userId()).param("hash", hash)
                    .param("run", created.runId()).param("sub", created.subRunId()).param("trace", created.traceId())
                    .param("owner", leaseOwner).param("expiry", Timestamp.from(leaseExpiry)).update();
            metric("new_owner");
            return created;
        } catch (RuntimeException race) {
            return acquireExisting(request, validate(find(request).orElseThrow(() -> race), hash));
        }
    }

    public Optional<ExecutionIdentity> find(ChatRequest request) {
        return db.sql("select run_id,sub_run_id,trace_id,status,request_hash,response_json,lease_owner,lease_expires_at from agent_request_dedup where tenant_id=:tenant and conversation_id=:conversation and message_id=:message")
                .param("tenant", request.tenantId()).param("conversation", request.conversationId())
                .param("message", request.messageId()).query((rs, row) -> {
                    ChatResponse response = null;
                    String value = rs.getString("response_json");
                    if (value != null && !value.isBlank()) {
                        try { response = json.readValue(value, ChatResponse.class); }
                        catch (Exception error) { throw new IllegalStateException("已保存的幂等响应无法反序列化", error); }
                    }
                    Timestamp lease = rs.getTimestamp("lease_expires_at");
                    return new ExecutionIdentity(rs.getString("run_id"), rs.getString("sub_run_id"),
                            rs.getString("trace_id"), rs.getString("status"), rs.getString("request_hash"), response,
                            false, rs.getString("lease_owner"), lease == null ? null : lease.toInstant());
                }).optional();
    }

    public void markRunning(ChatRequest request, ExecutionIdentity execution) {
        int changed = db.sql("update agent_request_dedup set status='RUNNING',error_type=null,error_message=null," +
                        "lease_expires_at=:expiry,updated_at=current_timestamp where tenant_id=:tenant and conversation_id=:conversation " +
                        "and message_id=:message and lease_owner=:owner")
                .param("expiry", Timestamp.from(Instant.now().plusSeconds(leaseSeconds)))
                .param("tenant", request.tenantId()).param("conversation", request.conversationId())
                .param("message", request.messageId()).param("owner", execution.leaseOwner()).update();
        if (changed != 1) throw new RequestInProgressException("消息执行租约已被其他请求持有，请稍后重试");
    }

    public void complete(ChatRequest request, ExecutionIdentity execution, ChatResponse response) {
        String serialized;
        try {
            serialized = json.writeValueAsString(response);
        } catch (Exception error) {
            throw new IllegalStateException("幂等响应序列化失败", error);
        }
        int changed = db.sql("update agent_request_dedup set status='COMPLETED',response_json=:response,error_type=null,error_message=null," +
                        "lease_owner=null,lease_expires_at=null,updated_at=current_timestamp where tenant_id=:tenant and conversation_id=:conversation " +
                        "and message_id=:message and lease_owner=:owner")
                .param("response", serialized).param("tenant", request.tenantId())
                .param("conversation", request.conversationId()).param("message", request.messageId())
                .param("owner", execution.leaseOwner()).update();
        if (changed != 1) throw new RequestInProgressException("消息执行租约已经转移，拒绝覆盖新的执行结果");
    }

    public void fail(ChatRequest request, ExecutionIdentity execution, Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        db.sql("update agent_request_dedup set status='FAILED',error_type=:type,error_message=:error,lease_owner=null," +
                        "lease_expires_at=null,updated_at=current_timestamp where tenant_id=:tenant and conversation_id=:conversation " +
                        "and message_id=:message and lease_owner=:owner")
                .param("type", error.getClass().getName()).param("error", message.substring(0, Math.min(message.length(), 2000)))
                .param("tenant", request.tenantId()).param("conversation", request.conversationId())
                .param("message", request.messageId()).param("owner", execution.leaseOwner()).update();
    }

    public ChatResponse awaitCompletion(ChatRequest request) {
        Instant deadline = Instant.now().plusSeconds(duplicateWaitSeconds);
        while (Instant.now().isBefore(deadline)) {
            ExecutionIdentity current = find(request).orElseThrow();
            if ("COMPLETED".equals(current.status()) && current.response() != null) return current.response();
            if ("FAILED".equals(current.status()))
                throw new ModelCallException("相同 messageId 的原请求执行失败，请更换 messageId 后重试");
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RequestInProgressException("等待相同消息完成时被中断");
            }
        }
        throw new RequestInProgressException("相同 messageId 的请求仍在执行，请稍后重试");
    }

    private ExecutionIdentity acquireExisting(ChatRequest request, ExecutionIdentity existing) {
        if ("COMPLETED".equals(existing.status()) && existing.response() != null) {
            metric("completed_replay");
            return existing;
        }
        boolean expired = existing.leaseExpiresAt() == null || existing.leaseExpiresAt().isBefore(Instant.now());
        if (!"FAILED".equals(existing.status()) && !expired) { metric("duplicate_wait"); return existing; }
        String owner = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plusSeconds(leaseSeconds);
        int claimed = db.sql("update agent_request_dedup set status='RUNNING',lease_owner=:owner,lease_expires_at=:expiry," +
                        "error_type=null,error_message=null,updated_at=current_timestamp where tenant_id=:tenant and conversation_id=:conversation " +
                        "and message_id=:message and (status='FAILED' or lease_expires_at is null or lease_expires_at<current_timestamp)")
                .param("owner", owner).param("expiry", Timestamp.from(expiry)).param("tenant", request.tenantId())
                .param("conversation", request.conversationId()).param("message", request.messageId()).update();
        if (claimed == 1) {
            metric("lease_recovered");
            return new ExecutionIdentity(existing.runId(), existing.subRunId(), existing.traceId(),
                    "RUNNING", existing.requestHash(), null, true, owner, expiry);
        }
        metric("claim_lost");
        return find(request).orElse(existing);
    }

    private ExecutionIdentity validate(ExecutionIdentity value, String requestHash) {
        if (!value.requestHash().equals(requestHash)) {
            metric("payload_conflict");
            throw new IllegalArgumentException("同一个 messageId 不能对应不同消息内容");
        }
        return value;
    }

    private void metric(String result) {
        if (meters != null) meters.counter("agent.request.dedup", "result", result).increment();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    public record ExecutionIdentity(String runId, String subRunId, String traceId, String status,
                                    String requestHash, ChatResponse response, boolean acquired,
                                    String leaseOwner, Instant leaseExpiresAt) {}

    public static final class RequestInProgressException extends RuntimeException {
        public RequestInProgressException(String message) { super(message); }
    }
}
