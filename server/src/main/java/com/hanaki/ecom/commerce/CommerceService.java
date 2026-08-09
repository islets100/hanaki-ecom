package com.hanaki.ecom.commerce;

import com.hanaki.ecom.domain.Domain.BalanceEntryView;
import com.hanaki.ecom.domain.Domain.BalanceView;
import com.hanaki.ecom.domain.Domain.Product;
import com.hanaki.ecom.domain.Domain.PurchaseRequest;
import com.hanaki.ecom.domain.Domain.PurchaseResponse;
import com.hanaki.ecom.domain.Domain.PlatformBalanceEntryView;
import com.hanaki.ecom.domain.Domain.PlatformBalanceView;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import com.hanaki.ecom.security.TenantService;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.agent.BusinessTaskStateMachine;
import com.hanaki.ecom.agent.ToolOperationLedger;
import com.hanaki.ecom.domain.Domain.ToolOperationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 演示商城的闭环资金与履约服务。
 * 余额、库存、订单、余额流水和物流计划在同一数据库事务中提交，任何一步失败都会整体回滚。
 */
@Service
public class CommerceService {
    private final JdbcClient db;
    private final EcommerceStore store;
    private final BusinessTaskStateMachine stateMachine;
    private final ToolOperationLedger operations;

    @Autowired
    public CommerceService(JdbcClient db, EcommerceStore store, BusinessTaskStateMachine stateMachine,
                           ToolOperationLedger operations) {
        this.db = db;
        this.store = store;
        this.stateMachine = stateMachine;
        this.operations = operations;
    }

    /** 不启动 Spring 容器的仓库测试使用；正式服务由上面的注入构造器复用独立流水 Bean。 */
    public CommerceService(JdbcClient db, EcommerceStore store, BusinessTaskStateMachine stateMachine) {
        this(db, store, stateMachine, new ToolOperationLedger(db));
    }

    public BalanceView balance(SessionAccount customer) {
        return db.sql("select available_balance,version from account_balance where account_id=:account and tenant_id=:tenant")
                .param("account", customer.id()).param("tenant", customer.tenantId())
                .query((rs, n) -> new BalanceView(rs.getBigDecimal("available_balance"), "CNY",
                        rs.getInt("version"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("账户余额尚未初始化"));
    }

    public List<BalanceEntryView> ledger(SessionAccount customer) {
        return db.sql("select * from balance_ledger where account_id=:account and tenant_id=:tenant order by created_at desc limit 50")
                .param("account", customer.id()).param("tenant", customer.tenantId())
                .query((rs, n) -> new BalanceEntryView(rs.getString("id"), rs.getString("entry_type"),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"),
                        rs.getString("reference_id"), rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    public PlatformBalanceView platformBalance(SessionAccount staff) {
        ensurePlatformBalance();
        return db.sql("select available_balance,version from platform_balance where tenant_id=:tenant")
                .param("tenant", TenantService.PLATFORM_TENANT_ID)
                .query((rs, row) -> new PlatformBalanceView(TenantService.PLATFORM_TENANT_ID,
                        rs.getBigDecimal("available_balance"), "CNY", rs.getInt("version"))).single();
    }

    public List<PlatformBalanceEntryView> platformLedger(SessionAccount staff) {
        return db.sql("select * from platform_balance_ledger where tenant_id=:tenant " +
                        "order by created_at desc limit 100")
                .param("tenant", TenantService.PLATFORM_TENANT_ID)
                .query((rs, row) -> new PlatformBalanceEntryView(rs.getString("id"),
                        rs.getString("entry_type"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after"), rs.getString("reference_id"),
                        rs.getString("description"), rs.getTimestamp("created_at").toInstant())).list();
    }

    @Transactional
    public PurchaseResponse purchase(SessionAccount customer, PurchaseRequest request) {
        if (request.productId() == null || request.productId().isBlank()) throw new IllegalArgumentException("请选择商品");
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString() : request.requestId().strip();
        if (requestId.length() > 120 || !requestId.matches("[A-Za-z0-9._:-]+"))
            throw new IllegalArgumentException("购买 requestId 格式无效");
        String requestHash = sha256(request.productId() + "|" + String.valueOf(request.sku()));
        PurchaseResponse replay = completedPurchase(customer, requestId, requestHash);
        if (replay != null) return replay;
        Product product = store.product(TenantService.PLATFORM_TENANT_ID, request.productId())
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        String merchantTenantId = product.tenantId();
        if (product.stock() <= 0) throw new IllegalArgumentException("商品暂时无库存");

        /*
         * 对当前用户的余额行加悲观锁。购买本来就必须串行修改这行余额，因此该锁不会扩大业务
         * 临界区，却能让相同 requestId 的竞争请求在插入幂等记录前排队。第一个事务提交后，
         * 后来的事务会在锁内再次读取幂等结果。这样不依赖捕获唯一键异常，也不会触发
         * PostgreSQL “约束异常后整笔事务不可继续使用”的行为。
         */
        BigDecimal balanceBefore = currentBalanceForUpdate(customer.id(), customer.tenantId());
        platformBalanceForUpdate();
        replay = completedPurchase(customer, requestId, requestHash);
        if (replay != null) return replay;
        PurchaseDedup existing = purchaseDedup(customer, requestId);
        if (existing != null) {
            validateRequestHash(existing, requestHash);
            throw new IllegalStateException("购买请求正在处理中，请使用相同 requestId 稍后重试");
        }

        Instant created = Instant.now();
        // 模拟物流使用确定时间：付款后 18 小时计划发货，发货后 72 小时预计送达。
        Instant plannedShip = created.plus(18, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
        Instant estimatedArrival = plannedShip.plus(72, ChronoUnit.HOURS);
        String orderId = "OD" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase(Locale.ROOT);
        String sku = request.sku() == null || request.sku().isBlank() ? "标准款" : request.sku().strip();

        db.sql("insert into purchase_request_dedup(tenant_id,user_id,request_id,request_hash,order_id,paid_amount," +
                        "balance_after,planned_ship_at,estimated_arrival_at,status,created_at) values(:tenant,:user,:request," +
                        ":hash,:orderId,:paid,:before,:ship,:arrival,'PROCESSING',current_timestamp)")
                .param("tenant", customer.tenantId()).param("user", customer.id()).param("request", requestId)
                .param("hash", requestHash).param("orderId", orderId).param("paid", product.price())
                .param("before", balanceBefore)
                .param("ship", Timestamp.from(plannedShip)).param("arrival", Timestamp.from(estimatedArrival)).update();

        int debited = db.sql("update account_balance set available_balance=available_balance-:amount," +
                        "version=version+1,updated_at=current_timestamp where account_id=:account and tenant_id=:tenant " +
                        "and available_balance>=:amount")
                .param("amount", product.price()).param("account", customer.id())
                .param("tenant", customer.tenantId()).update();
        if (debited != 1) throw new IllegalArgumentException("余额不足，无法完成购买");
        int stocked = db.sql("update product set stock=stock-1 where id=:product and tenant_id=:tenant and stock>0")
                .param("product", product.id()).param("tenant", merchantTenantId).update();
        if (stocked != 1) throw new IllegalArgumentException("库存已发生变化，请刷新后重试");

        db.sql("update platform_balance set available_balance=available_balance+:amount,version=version+1," +
                        "updated_at=current_timestamp where tenant_id=:tenant")
                .param("amount", product.price()).param("tenant", TenantService.PLATFORM_TENANT_ID).update();
        BigDecimal platformAfter = currentPlatformBalance();

        db.sql("insert into customer_order(id,tenant_id,user_id,product_id,sku,amount,status,payment_status,logistics_status,created_at) " +
                        "values(:id,:tenant,:user,:product,:sku,:amount,'PROCESSING','BALANCE_PAID','待发货',:created)")
                .param("id", orderId).param("tenant", merchantTenantId).param("user", customer.id())
                .param("product", product.id()).param("sku", sku).param("amount", product.price())
                .param("created", Timestamp.from(created)).update();
        db.sql("insert into order_fulfillment(order_id,tenant_id,store_id,planned_ship_at,estimated_arrival_at,status,updated_at) " +
                        "values(:orderId,:tenant,:store,:ship,:arrival,'PLANNED',current_timestamp)")
                .param("orderId", orderId).param("tenant", merchantTenantId).param("store", product.storeId())
                .param("ship", Timestamp.from(plannedShip)).param("arrival", Timestamp.from(estimatedArrival)).update();
        db.sql("insert into logistics_event(id,tenant_id,order_id,event_time,location,description) " +
                        "values(:id,:tenant,:orderId,:time,'花木商城','余额支付成功；商家已确认计划发货和预计到达时间')")
                .param("id", "LE-" + shortId()).param("tenant", merchantTenantId).param("orderId", orderId)
                .param("time", Timestamp.from(created)).update();

        BigDecimal after = currentBalance(customer.id(), customer.tenantId());
        addLedger(customer.id(), customer.tenantId(), "PURCHASE", product.price().negate(), after,
                orderId, "购买「" + product.name() + "」");
        addPlatformLedger("PAYMENT", product.price(), platformAfter,
                orderId, "订单收款「" + product.name() + "」");
        PurchaseResponse response = new PurchaseResponse(orderId, product.price(), after, plannedShip, estimatedArrival, "PAID");
        db.sql("update purchase_request_dedup set balance_after=:after,status='PAID' " +
                        "where tenant_id=:tenant and user_id=:user and request_id=:request and request_hash=:hash")
                .param("after", after).param("tenant", customer.tenantId()).param("user", customer.id())
                .param("request", requestId).param("hash", requestHash).update();
        return response;
    }

    private PurchaseResponse completedPurchase(SessionAccount customer, String requestId, String requestHash) {
        PurchaseDedup row = purchaseDedup(customer, requestId);
        if (row == null) return null;
        validateRequestHash(row, requestHash);
        if (!"PAID".equals(row.status())) return null;
        return new PurchaseResponse(row.orderId(), row.paidAmount(), row.balanceAfter(),
                row.plannedShipAt(), row.estimatedArrivalAt(), row.status());
    }

    private PurchaseDedup purchaseDedup(SessionAccount customer, String requestId) {
        return db.sql("select request_hash,order_id,paid_amount,balance_after,planned_ship_at," +
                        "estimated_arrival_at,status from purchase_request_dedup where tenant_id=:tenant and user_id=:user " +
                        "and request_id=:request")
                .param("tenant", customer.tenantId()).param("user", customer.id()).param("request", requestId)
                .query((rs, row) -> new PurchaseDedup(rs.getString("request_hash"), rs.getString("order_id"),
                        rs.getBigDecimal("paid_amount"), rs.getBigDecimal("balance_after"),
                        rs.getTimestamp("planned_ship_at").toInstant(),
                        rs.getTimestamp("estimated_arrival_at").toInstant(), rs.getString("status")))
                .optional().orElse(null);
    }

    private void validateRequestHash(PurchaseDedup row, String requestHash) {
        if (!requestHash.equals(row.requestHash()))
            throw new IllegalArgumentException("同一个购买 requestId 不能对应不同商品");
    }

    /** 自动判定或店铺审核通过后的唯一退款入口；只有余额支付且未退款的本人订单可以回滚额度。 */
    @Transactional
    public BigDecimal refundApprovedTask(String tenantId, String customerId, String businessTaskId) {
        String accountTenantId = accountTenant(customerId);
        String taskStatus = stateMachine.status(businessTaskId, tenantId);
        if ("REFUNDED".equals(taskStatus)) return currentBalance(customerId, accountTenantId);
        RefundTarget target = db.sql("select b.order_id,o.product_id,o.amount,o.payment_status,p.name product_name " +
                        "from business_task b join customer_order o on o.id=b.order_id and o.tenant_id=b.tenant_id " +
                        "join product p on p.id=o.product_id and p.tenant_id=o.tenant_id " +
                        "where b.id=:task and b.tenant_id=:tenant and b.user_id=:user")
                .param("task", businessTaskId).param("tenant", tenantId).param("user", customerId)
                .query((rs, n) -> new RefundTarget(rs.getString("order_id"), rs.getString("product_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("payment_status"), rs.getString("product_name"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("退款任务未关联到当前客户订单"));
        String requestDigest = sha256(String.join("|", tenantId, customerId, businessTaskId,
                target.orderId(), target.amount().stripTrailingZeros().toPlainString(), "REFUND"));

        /*
         * 若上次在写操作与 Graph/业务快照之间宕机，流水可能仍是 EXECUTING/UNKNOWN。此处先查询
         * 权威订单状态：已退款则补记 SUCCEEDED；仍为余额支付说明本地事务没有提交，可以显式允许
         * 重试。未来替换为外部退款 API 时，这一步必须改为按外部流水号查单，不能仅凭本地状态猜测。
         */
        ToolOperationLedger.OperationSnapshot previous = operations.find(
                tenantId, businessTaskId, "REFUND");
        if (previous != null && !previous.requestDigest().equals(requestDigest))
            throw new SecurityException("退款任务的执行参数摘要与原操作流水不一致");
        if (previous != null && previous.status() == ToolOperationStatus.SUCCEEDED)
            return currentBalance(customerId, accountTenantId);
        if (previous != null && (previous.status() == ToolOperationStatus.EXECUTING
                || previous.status() == ToolOperationStatus.UNKNOWN)) {
            if ("REFUNDED".equals(target.paymentStatus())) {
                operations.succeeded(previous.operationId(), target.orderId(),
                        "{\"status\":\"REFUNDED\"}");
                return currentBalance(customerId, accountTenantId);
            }
            operations.allowRetryAfterReconciliation(previous.operationId(),
                    "BALANCE_PAID".equals(target.paymentStatus()));
        }

        ToolOperationLedger.OperationSnapshot operation = operations.begin(
                tenantId, businessTaskId, "REFUND", requestDigest);
        try {
            stateMachine.transitionRequired(businessTaskId, tenantId, "APPROVED", "EXECUTING");
            if ("REFUNDED".equals(target.paymentStatus())) {
                stateMachine.transitionRequired(businessTaskId, tenantId, "EXECUTING", "REFUNDED");
                BigDecimal balance = currentBalance(customerId, accountTenantId);
                markOperationSucceededAfterCommit(operation.operationId(), target.orderId(), balance);
                return balance;
            }
            if (!"BALANCE_PAID".equals(target.paymentStatus()))
                throw new IllegalArgumentException("该订单不是余额支付，不能执行余额回滚");

            // 资金锁顺序与购买一致：先锁平台客户余额，再锁全局平台余额，避免并发支付/退款死锁。
            currentBalanceForUpdate(customerId, accountTenantId);
            platformBalanceForUpdate();

            int changed = db.sql("update customer_order set payment_status='REFUNDED',status='REFUNDED'," +
                            "logistics_status='已关闭' where id=:orderId and tenant_id=:tenant and user_id=:user " +
                            "and payment_status='BALANCE_PAID'")
                    .param("orderId", target.orderId()).param("tenant", tenantId)
                    .param("user", customerId).update();
            if (changed != 1) throw new IllegalArgumentException("订单支付状态已变化，请刷新后重试");
            int restocked = db.sql("update product set stock=stock+1 where id=:product and tenant_id=:tenant")
                    .param("product", target.productId()).param("tenant", tenantId).update();
            if (restocked != 1) throw new IllegalStateException("退款订单关联的商品不存在，无法安全回补库存");
            db.sql("update order_fulfillment set status='CANCELLED',updated_at=current_timestamp " +
                            "where order_id=:orderId and tenant_id=:tenant and status<>'CANCELLED'")
                    .param("orderId", target.orderId()).param("tenant", tenantId).update();
            db.sql("insert into logistics_event(id,tenant_id,order_id,event_time,location,description) " +
                            "values(:id,:tenant,:orderId,current_timestamp,'花木商城','退款已完成；订单履约已关闭，商品库存已回补')")
                    .param("id", "LE-" + shortId()).param("tenant", tenantId)
                    .param("orderId", target.orderId()).update();
            db.sql("update account_balance set available_balance=available_balance+:amount,version=version+1," +
                            "updated_at=current_timestamp where account_id=:account and tenant_id=:tenant")
                    .param("amount", target.amount()).param("account", customerId)
                    .param("tenant", accountTenantId).update();
            int platformDebited = db.sql("update platform_balance set available_balance=available_balance-:amount," +
                            "version=version+1,updated_at=current_timestamp where tenant_id=:tenant " +
                            "and available_balance>=:amount")
                    .param("amount", target.amount()).param("tenant", TenantService.PLATFORM_TENANT_ID).update();
            if (platformDebited != 1) {
                throw new IllegalStateException("平台余额不足，退款资金账本校验未通过");
            }
            BigDecimal after = currentBalance(customerId, accountTenantId);
            BigDecimal platformAfter = currentPlatformBalance();
            addLedger(customerId, accountTenantId, "REFUND", target.amount(), after, target.orderId(),
                    "退款「" + target.productName() + "」");
            addPlatformLedger("REFUND", target.amount().negate(), platformAfter,
                    target.orderId(), "订单退款「" + target.productName() + "」");
            stateMachine.transitionRequired(businessTaskId, tenantId, "EXECUTING", "REFUNDED");
            markOperationSucceededAfterCommit(operation.operationId(), target.orderId(), after);
            return after;
        } catch (RuntimeException error) {
            operations.failed(operation.operationId(), error);
            throw error;
        }
    }

    /**
     * 只有业务事务真正提交后才把操作流水标记为 SUCCEEDED；否则“流水成功、余额事务回滚”会造成
     * 恢复器错误地跳过退款。直接单元测试没有 Spring 事务同步器时，业务 SQL 已逐条提交，可立即记录。
     */
    private void markOperationSucceededAfterCommit(String operationId, String orderId, BigDecimal balanceAfter) {
        Runnable marker = () -> operations.succeeded(operationId, orderId,
                "{\"status\":\"REFUNDED\",\"balanceAfter\":\"" + balanceAfter.toPlainString() + "\"}");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            marker.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { marker.run(); }
        });
    }

    private void addLedger(String accountId, String tenantId, String type, BigDecimal amount,
                           BigDecimal after, String referenceId, String description) {
        db.sql("insert into balance_ledger(id,account_id,tenant_id,entry_type,amount,balance_after,reference_id,description,created_at) " +
                        "values(:id,:account,:tenant,:type,:amount,:after,:reference,:description,current_timestamp)")
                .param("id", "BL-" + shortId()).param("account", accountId).param("tenant", tenantId)
                .param("type", type).param("amount", amount).param("after", after)
                .param("reference", referenceId).param("description", description).update();
    }

    private void addPlatformLedger(String type, BigDecimal amount,
                                   BigDecimal after, String referenceId, String description) {
        db.sql("insert into platform_balance_ledger(id,tenant_id,entry_type,amount,balance_after," +
                        "reference_id,description,created_at) values(:id,:tenant,:type,:amount,:after," +
                        ":reference,:description,current_timestamp)")
                .param("id", "PBL-" + shortId()).param("tenant", TenantService.PLATFORM_TENANT_ID).param("type", type)
                .param("amount", amount).param("after", after).param("reference", referenceId)
                .param("description", description).update();
    }

    private void ensurePlatformBalance() {
        db.sql("insert into platform_balance(tenant_id,available_balance,version,updated_at) " +
                        "values(:tenant,0,0,current_timestamp) on conflict do nothing")
                .param("tenant", TenantService.PLATFORM_TENANT_ID).update();
    }

    private BigDecimal currentPlatformBalance() {
        return db.sql("select available_balance from platform_balance where tenant_id=:tenant")
                .param("tenant", TenantService.PLATFORM_TENANT_ID).query(BigDecimal.class).single();
    }

    private BigDecimal platformBalanceForUpdate() {
        ensurePlatformBalance();
        return db.sql("select available_balance from platform_balance where tenant_id=:tenant for update")
                .param("tenant", TenantService.PLATFORM_TENANT_ID).query(BigDecimal.class).single();
    }

    private String accountTenant(String accountId) {
        return db.sql("select tenant_id from app_account where id=:account and role='CUSTOMER'")
                .param("account", accountId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("客户账户不存在"));
    }
    private BigDecimal currentBalance(String accountId, String tenantId) {
        return db.sql("select available_balance from account_balance where account_id=:account and tenant_id=:tenant")
                .param("account", accountId).param("tenant", tenantId).query(BigDecimal.class).single();
    }

    private BigDecimal currentBalanceForUpdate(String accountId, String tenantId) {
        return db.sql("select available_balance from account_balance where account_id=:account and tenant_id=:tenant for update")
                .param("account", accountId).param("tenant", tenantId).query(BigDecimal.class).single();
    }
    private String shortId() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private record RefundTarget(String orderId, String productId, BigDecimal amount,
                                String paymentStatus, String productName) {}
    private record PurchaseDedup(String requestHash, String orderId, BigDecimal paidAmount,
                                 BigDecimal balanceAfter, Instant plannedShipAt,
                                 Instant estimatedArrivalAt, String status) {}
}
