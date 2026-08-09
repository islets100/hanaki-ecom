package com.hanaki.ecom.commerce;

import com.hanaki.ecom.agent.BusinessTaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 把旧版“店铺已同意、等待官方复审”的任务迁移到现在的店铺直接退款规则。 */
@Component
public class LegacyRefundApprovalMigrator {
    private static final Logger log = LoggerFactory.getLogger(LegacyRefundApprovalMigrator.class);
    private final JdbcClient db;
    private final BusinessTaskStateMachine stateMachine;
    private final CommerceService commerce;
    private final TransactionTemplate transactions;

    public LegacyRefundApprovalMigrator(JdbcClient db, BusinessTaskStateMachine stateMachine,
                                        CommerceService commerce, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.stateMachine = stateMachine;
        this.commerce = commerce;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateApprovedStoreRefunds() {
        List<LegacyTask> tasks = db.sql("select distinct b.id,b.tenant_id,b.user_id,c.id case_id,d.decided_by " +
                        "from business_task b join staff_decision d on d.business_task_id=b.id " +
                        "left join support_case c on c.business_task_id=b.id and c.tenant_id=b.tenant_id " +
                        "where b.status='WAITING_OFFICIAL_APPROVAL' and d.stage='STORE' and d.decision='APPROVE'")
                .query((rs, row) -> new LegacyTask(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("user_id"), rs.getString("case_id"), rs.getString("decided_by")))
                .list();
        for (LegacyTask task : tasks) {
            try {
                transactions.executeWithoutResult(status -> migrateOne(task));
            } catch (RuntimeException error) {
                log.error("Legacy store-approved refund migration failed for task {}", task.taskId(), error);
            }
        }
        if (!tasks.isEmpty()) log.info("Processed {} legacy store-approved refund task(s)", tasks.size());
    }

    private void migrateOne(LegacyTask task) {
        if (!stateMachine.tryTransition(task.taskId(), task.tenantId(),
                "WAITING_OFFICIAL_APPROVAL", "APPROVED")) return;
        BigDecimal balance = commerce.refundApprovedTask(task.tenantId(), task.customerId(), task.taskId());
        if (task.caseId() == null) return;
        db.sql("update support_case set queue_name='STORE',status='RESOLVED',assignee_id=:staff," +
                        "updated_at=current_timestamp where id=:id")
                .param("staff", task.decidedBy()).param("id", task.caseId()).update();
        db.sql("insert into support_message(id,case_id,sender_id,sender_role,content,created_at) " +
                        "values(:id,:caseId,:sender,'STORE_AGENT',:content,current_timestamp)")
                .param("id", "MSG-" + shortId()).param("caseId", task.caseId())
                .param("sender", task.decidedBy())
                .param("content", "店铺客服此前已经同意退款。系统已按新规则取消官方复审并直接退款，当前余额 ¥" +
                        balance.toPlainString()).update();
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private record LegacyTask(String taskId, String tenantId, String customerId,
                              String caseId, String decidedBy) {}
}
