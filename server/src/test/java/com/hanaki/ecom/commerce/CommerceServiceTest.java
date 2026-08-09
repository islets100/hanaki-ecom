package com.hanaki.ecom.commerce;

import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.PurchaseRequest;
import com.hanaki.ecom.domain.Domain.PurchaseResponse;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.agent.BusinessTaskStateMachine;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceServiceTest {
    private JdbcClient db;
    private CommerceService commerce;
    private EcommerceStore store;
    private SessionAccount customer;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:commerce-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), dataSource);
        db = JdbcClient.create(dataSource);
        db.sql("insert into saas_tenant(tenant_id,tenant_code,display_name,status,tenant_type,primary_store_id," +
                "store_agent_invite_hash,official_agent_invite_hash,created_at,updated_at) values(" +
                "'merchant','merchant','测试商家','ACTIVE','MERCHANT','STORE-1','x','x',current_timestamp,current_timestamp)").update();
        db.sql("insert into app_account(id,tenant_id,username,password_hash,display_name,role,enabled,created_at) " +
                "values('customer','platform','buyer','hash','购买测试用户','CUSTOMER',true,current_timestamp)").update();
        db.sql("insert into account_balance(account_id,tenant_id,available_balance,version,updated_at) " +
                "values('customer','platform',10000,0,current_timestamp)").update();
        db.sql("insert into merchant_store(id,tenant_id,name,logo_text,description,service_score,fulfillment_score,location,created_at) " +
                "values('STORE-1','merchant','测试店铺','测','测试',5,5,'上海',current_timestamp)").update();
        db.sql("insert into product(id,tenant_id,store_id,name,subtitle,category,price,old_price,stock,badge,attributes_json) " +
                "values('P1','merchant','STORE-1','三十元商品','测试商品','测试',30,35,2,'测试','{}')").update();
        store = new EcommerceStore(db);
        commerce = new CommerceService(db, store, new BusinessTaskStateMachine(store));
        customer = new SessionAccount("customer", "platform", "buyer", "购买测试用户", AccountRole.CUSTOMER, null);
    }

    @Test
    void successfulBalancePaymentCreatesOrderFulfillmentAndInitialLogisticsEvent() {
        PurchaseResponse response = commerce.purchase(customer, new PurchaseRequest("P1", "标准款"));

        assertThat(response.paidAmount()).isEqualByComparingTo("30");
        assertThat(response.balanceAfter()).isEqualByComparingTo("9970");
        assertThat(response.plannedShipAt()).isBefore(response.estimatedArrivalAt());
        assertThat(db.sql("select count(*) from customer_order where id=:id and payment_status='BALANCE_PAID'")
                .param("id", response.orderId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from order_fulfillment where order_id=:id and status='PLANNED'")
                .param("id", response.orderId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from logistics_event where order_id=:id")
                .param("id", response.orderId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from balance_ledger where reference_id=:id and entry_type='PURCHASE'")
                .param("id", response.orderId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select tenant_id from customer_order where id=:id")
                .param("id", response.orderId()).query(String.class).single()).isEqualTo("merchant");
        assertThat(db.sql("select available_balance from platform_balance where tenant_id='platform'")
                .query(BigDecimal.class).single()).isEqualByComparingTo("30");
        assertThat(db.sql("select count(*) from platform_balance_ledger where reference_id=:id and entry_type='PAYMENT'")
                .param("id", response.orderId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void samePurchaseRequestIdReturnsOriginalOrderWithoutChargingTwice() {
        PurchaseRequest request = new PurchaseRequest("P1", "标准款", "checkout-request-001");

        PurchaseResponse first = commerce.purchase(customer, request);
        PurchaseResponse replay = commerce.purchase(customer, request);

        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.balanceAfter()).isEqualByComparingTo("9970");
        assertThat(db.sql("select count(*) from customer_order").query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from balance_ledger where entry_type='PURCHASE'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from platform_balance_ledger where entry_type='PAYMENT'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select available_balance from platform_balance where tenant_id='platform'")
                .query(BigDecimal.class).single()).isEqualByComparingTo("30");
        assertThat(db.sql("select stock from product where id='P1'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void approvedRefundUsesOperationLedgerAndReplayDoesNotCreditTwice() {
        PurchaseResponse purchase = commerce.purchase(customer,
                new PurchaseRequest("P1", "标准款", "refund-source-order"));
        String taskId = store.createBusinessTask("merchant", "customer", purchase.orderId(),
                "REFUND", "APPROVED", "after-sale-v12", "refund-business-task");

        BigDecimal first = commerce.refundApprovedTask("merchant", "customer", taskId);
        BigDecimal replay = commerce.refundApprovedTask("merchant", "customer", taskId);

        assertThat(first).isEqualByComparingTo("10000");
        assertThat(replay).isEqualByComparingTo(first);
        assertThat(db.sql("select status from tool_operation_record where tenant_id='merchant' " +
                        "and business_task_id=:task and operation_type='REFUND'")
                .param("task", taskId).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(db.sql("select count(*) from balance_ledger where entry_type='REFUND' and reference_id=:order")
                .param("order", purchase.orderId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("select available_balance from platform_balance where tenant_id='platform'")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(db.sql("select count(*) from platform_balance_ledger where reference_id=:order")
                .param("order", purchase.orderId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(db.sql("select stock from product where id='P1'")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(db.sql("select status from order_fulfillment where order_id=:order")
                .param("order", purchase.orderId()).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(db.sql("select count(*) from logistics_event where order_id=:order")
                .param("order", purchase.orderId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(db.sql("select logistics_status from customer_order where id=:order")
                .param("order", purchase.orderId()).query(String.class).single()).isEqualTo("已关闭");
    }

    @Test
    void concurrentSameRequestIdHasOneBusinessSideEffectAndBothCallersReceiveSameOrder() throws Exception {
        PurchaseRequest request = new PurchaseRequest("P1", "标准款", "concurrent-checkout-001");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PurchaseResponse> first = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> commerce.purchase(customer, request));
            });
            Future<PurchaseResponse> second = executor.submit(() -> {
                start.await();
                return transactions.execute(status -> commerce.purchase(customer, request));
            });
            start.countDown();
            PurchaseResponse left = first.get();
            PurchaseResponse right = second.get();

            assertThat(right.orderId()).isEqualTo(left.orderId());
            assertThat(db.sql("select count(*) from customer_order").query(Integer.class).single()).isEqualTo(1);
            assertThat(db.sql("select available_balance from account_balance where account_id='customer'")
                    .query(BigDecimal.class).single()).isEqualByComparingTo("9970");
            assertThat(db.sql("select stock from product where id='P1'").query(Integer.class).single()).isEqualTo(1);
        }
    }
}
