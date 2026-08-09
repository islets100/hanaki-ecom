package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.ToolOperationStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 外部写操作的幂等流水。
 *
 * <p>Graph Checkpoint 可能在“下游已经成功、节点快照尚未保存”之间宕机，因此恢复节点不能直接
 * 再调用退款/取消订单接口。本流水以 tenantId + businessTaskId + operationType 唯一标识操作，
 * 保存请求摘要和 INIT → EXECUTING → SUCCEEDED/FAILED/UNKNOWN 状态。UNKNOWN 必须先查询下游状态；
 * 只有下游明确返回“未执行”后，调用方才可以显式允许重试。</p>
 *
 * <p>流水方法使用 REQUIRES_NEW，使业务事务回滚时仍能保留“曾经尝试过/结果未知”的恢复线索。
 * 演示商城的退款发生在同一数据库事务中，通常不会落到 UNKNOWN；该协议仍为未来 HTTP/RPC 工具
 * 提供正确边界。</p>
 */
@Service
public class ToolOperationLedger {
    private final JdbcClient db;

    public ToolOperationLedger(JdbcClient db) { this.db = db; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationSnapshot begin(String tenantId, String businessTaskId,
                                   String operationType, String requestDigest) {
        OperationSnapshot current = find(tenantId, businessTaskId, operationType);
        if (current != null) {
            if (!current.requestDigest().equals(requestDigest))
                throw new SecurityException("同一业务任务的写操作参数摘要发生变化");
            if (current.status() == ToolOperationStatus.SUCCEEDED
                    || current.status() == ToolOperationStatus.EXECUTING
                    || current.status() == ToolOperationStatus.UNKNOWN) return current;
            db.sql("update tool_operation_record set status='EXECUTING',started_at=current_timestamp," +
                            "last_error=null,updated_at=current_timestamp where operation_id=:id")
                    .param("id", current.operationId()).update();
            return current.withStatus(ToolOperationStatus.EXECUTING);
        }

        String operationId = "OP-" + UUID.randomUUID();
        db.sql("insert into tool_operation_record(operation_id,tenant_id,business_task_id,operation_type," +
                        "request_digest,status,created_at,started_at,updated_at) values(:id,:tenant,:task,:type," +
                        ":digest,'EXECUTING',current_timestamp,current_timestamp,current_timestamp)")
                .param("id", operationId).param("tenant", tenantId).param("task", businessTaskId)
                .param("type", operationType).param("digest", requestDigest).update();
        return new OperationSnapshot(operationId, tenantId, businessTaskId, operationType,
                requestDigest, ToolOperationStatus.EXECUTING, null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(String operationId, String externalReference, String safeResultJson) {
        db.sql("update tool_operation_record set status='SUCCEEDED',external_reference=:external," +
                        "result_json=:result,last_error=null,completed_at=current_timestamp," +
                        "updated_at=current_timestamp where operation_id=:id")
                .param("external", externalReference).param("result", safeResultJson)
                .param("id", operationId).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(String operationId, Throwable error) {
        String message = error == null ? "unknown" : String.valueOf(error.getMessage());
        db.sql("update tool_operation_record set status='FAILED',last_error=:error," +
                        "completed_at=current_timestamp,updated_at=current_timestamp where operation_id=:id")
                .param("error", clip(message)).param("id", operationId).update();
    }

    /** 超时或连接中断不能标成 FAILED，因为下游可能已经提交成功。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unknown(String operationId, Throwable error) {
        String message = error == null ? "downstream status unknown" : String.valueOf(error.getMessage());
        db.sql("update tool_operation_record set status='UNKNOWN',last_error=:error," +
                        "updated_at=current_timestamp where operation_id=:id")
                .param("error", clip(message)).param("id", operationId).update();
    }

    /**
     * 下游按同一 tenantId/业务主键明确返回“没有执行记录”后，恢复器才可把 UNKNOWN 变回 INIT。
     * 下一次 begin() 会从 INIT 进入 EXECUTING；没有 verifiedAbsent=true 时拒绝状态倒退。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void allowRetryAfterReconciliation(String operationId, boolean verifiedAbsent) {
        if (!verifiedAbsent) throw new IllegalArgumentException("未确认下游无执行结果，禁止重试写操作");
        int changed = db.sql("update tool_operation_record set status='INIT',last_error=null," +
                        "updated_at=current_timestamp where operation_id=:id and status in ('UNKNOWN','EXECUTING')")
                .param("id", operationId).update();
        if (changed != 1) throw new IllegalStateException("操作流水当前状态不允许恢复重试");
    }

    public OperationSnapshot find(String tenantId, String businessTaskId, String operationType) {
        return db.sql("select operation_id,tenant_id,business_task_id,operation_type,request_digest,status," +
                        "external_reference,result_json,last_error from tool_operation_record " +
                        "where tenant_id=:tenant and business_task_id=:task and operation_type=:type")
                .param("tenant", tenantId).param("task", businessTaskId).param("type", operationType)
                .query((rs, row) -> new OperationSnapshot(rs.getString("operation_id"),
                        rs.getString("tenant_id"), rs.getString("business_task_id"),
                        rs.getString("operation_type"), rs.getString("request_digest"),
                        ToolOperationStatus.valueOf(rs.getString("status")),
                        rs.getString("external_reference"), rs.getString("result_json"),
                        rs.getString("last_error")))
                .optional().orElse(null);
    }

    private String clip(String value) {
        String safe = value == null ? "" : value;
        return safe.substring(0, Math.min(safe.length(), 2_000));
    }

    public record OperationSnapshot(String operationId, String tenantId, String businessTaskId,
                                    String operationType, String requestDigest,
                                    ToolOperationStatus status, String externalReference,
                                    String resultJson, String lastError) {
        OperationSnapshot withStatus(ToolOperationStatus next) {
            return new OperationSnapshot(operationId, tenantId, businessTaskId, operationType,
                    requestDigest, next, externalReference, resultJson, lastError);
        }
    }
}
