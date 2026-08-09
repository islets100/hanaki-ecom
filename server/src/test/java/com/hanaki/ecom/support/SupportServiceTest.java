package com.hanaki.ecom.support;

import com.hanaki.ecom.commerce.CommerceService;
import com.hanaki.ecom.agent.BusinessTaskStateMachine;
import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SupportServiceTest {
    private JdbcClient db;
    private SupportService support;
    private CommerceService commerce;
    private BusinessTaskStateMachine stateMachine;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:support-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), dataSource);
        db = JdbcClient.create(dataSource);
        db.sql("insert into saas_tenant(tenant_id,tenant_code,display_name,status,tenant_type,primary_store_id," +
                "store_agent_invite_hash,official_agent_invite_hash,created_at,updated_at) values(" +
                "'tenant','tenant','生活商家','ACTIVE','MERCHANT','STORE-LIVING','x','x',current_timestamp,current_timestamp)").update();
        db.sql("insert into merchant_store(id,tenant_id,name,logo_text,description,service_score," +
                "fulfillment_score,location,created_at) values('STORE-LIVING','tenant','生活店','生','测试',5,5," +
                "'上海',current_timestamp)").update();
        db.sql("insert into product(id,tenant_id,store_id,name,subtitle,category,price,old_price,stock,badge," +
                "attributes_json) values('P1','tenant','STORE-LIVING','测试商品','测试','家居',30,35,2,'测试','{}')")
                .update();
        commerce = mock(CommerceService.class);
        stateMachine = mock(BusinessTaskStateMachine.class);
        support = new SupportService(db, commerce, stateMachine);
    }

    @Test
    void startsWithAiAndCreatesCorrectStoreCaseOnlyAfterHandoff() {
        SupportService.StoreAiSession session = support.startStoreAi("tenant", "customer", "P1");

        assertThat(session.storeId()).isEqualTo("STORE-LIVING");
        assertThat(session.greeting()).contains("智能客服", "转人工");
        assertThat(support.storeAiContext("tenant", "customer", session.conversationId()))
                .hasValueSatisfying(context -> assertThat(context).contains("STORE-LIVING", "P1"));
        assertThat(countCases()).isZero();

        String firstCase = support.createHandoff("tenant", "customer", session.conversationId(), "请转人工", false);
        String replayedCase = support.createHandoff("tenant", "customer", session.conversationId(), "请转人工", false);

        assertThat(countCases()).isEqualTo(1);
        assertThat(replayedCase).isEqualTo(firstCase);
        assertThat(db.sql("select store_id from support_case").query(String.class).single())
                .isEqualTo("STORE-LIVING");
        assertThat(db.sql("select queue_name from support_case").query(String.class).single())
                .isEqualTo("STORE");
    }

    @Test
    void officialAiLoadsOwnedOrderLogisticsAndSupportHistoryBeforeOfficialHandoff() {
        db.sql("insert into customer_order(id,tenant_id,user_id,product_id,sku,amount,status,payment_status," +
                "logistics_status,created_at) values('OD10001','tenant','customer','P1','标准款',30,'PAID'," +
                "'BALANCE_PAID','IN_TRANSIT',current_timestamp)").update();
        db.sql("insert into order_fulfillment(order_id,tenant_id,store_id,planned_ship_at,estimated_arrival_at," +
                "status,updated_at) values('OD10001','tenant','STORE-LIVING',current_timestamp," +
                "dateadd('DAY',3,current_timestamp),'IN_TRANSIT',current_timestamp)").update();
        db.sql("insert into logistics_event(id,tenant_id,order_id,event_time,location,description) values(" +
                "'LE1','tenant','OD10001',current_timestamp,'上海','包裹运输中')").update();

        SupportService.StoreAiSession storeSession = support.startStoreAi("tenant", "customer", "P1");
        db.sql("insert into conversation_message(id,tenant_id,user_id,conversation_id,run_id,role,content,created_at) " +
                        "values('CM1','tenant','customer',:conversation,'RUN1','USER','我问过店铺何时发货',current_timestamp)")
                .param("conversation", storeSession.conversationId()).update();
        String storeCase = support.createHandoff("tenant", "customer", storeSession.conversationId(), "店铺人工记录", false);

        SupportService.OfficialAiSession official = support.startOfficialAi("platform", "customer");

        assertThat(official.greeting()).contains("OD10001", "官方智能客服", "物流", "聊天记录");
        assertThat(support.officialAiContext("platform", "customer", official.conversationId()))
                .hasValueSatisfying(context -> assertThat(context).contains(
                        "OD10001", "包裹运输中", "我问过店铺何时发货", storeCase, "店铺人工记录"));

        support.createHandoff("platform", "customer", official.conversationId(), "请转人工", false);
        assertThat(db.sql("select queue_name from support_case where conversation_id=:conversation")
                .param("conversation", official.conversationId()).query(String.class).single()).isEqualTo("OFFICIAL");
    }

    @Test
    void twoStoreAgentsCannotClaimTheSameCase() throws Exception {
        SupportService.StoreAiSession session = support.startStoreAi("tenant", "customer", "P1");
        String caseId = support.createHandoff("tenant", "customer", session.conversationId(), "需要人工", false);
        SessionAccount left = new SessionAccount("staff-left", "tenant", "left", "左客服",
                AccountRole.STORE_AGENT, "STORE-LIVING");
        SessionAccount right = new SessionAccount("staff-right", "tenant", "right", "右客服",
                AccountRole.STORE_AGENT, "STORE-LIVING");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> claimAfterStart(start, caseId, left));
            Future<String> second = executor.submit(() -> claimAfterStart(start, caseId, right));
            start.countDown();
            int successes = 0;
            for (Future<String> future : java.util.List.of(first, second)) {
                try {
                    assertThat(future.get()).isIn("staff-left", "staff-right");
                    successes++;
                } catch (ExecutionException expectedLoser) {
                    assertThat(expectedLoser.getCause()).isInstanceOf(IllegalArgumentException.class);
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(db.sql("select count(*) from support_message where case_id=:id and content like '%已接管会话%'")
                    .param("id", caseId).query(Integer.class).single()).isEqualTo(1);
        }
    }

    @Test
    void storeApprovalDirectlyRefundsAndNeverEntersOfficialQueue() {
        db.sql("insert into customer_order(id,tenant_id,user_id,product_id,sku,amount,status,payment_status," +
                "logistics_status,created_at) values('OD-R1','tenant','customer','P1','标准款',30,'PAID'," +
                "'BALANCE_PAID','待发货',current_timestamp)").update();
        db.sql("insert into business_task(id,tenant_id,user_id,order_id,type,status,rule_version,version," +
                "created_at,updated_at) values('BT-R1','tenant','customer','OD-R1','REFUND'," +
                "'WAITING_STORE_APPROVAL','v1',0,current_timestamp,current_timestamp)").update();
        db.sql("insert into refund_assessment(business_task_id,tenant_id,user_id,order_id,reason_text,score," +
                "policy_eligible,decision_mode,summary,matched_rule_ids,missing_information,rule_version," +
                "model_version,created_at,updated_at) values('BT-R1','tenant','customer','OD-R1'," +
                "'尺寸不合适',45,false,'STORE_REVIEW','需店铺审核','[]','[]','rv1','qwen-plus'," +
                "current_timestamp,current_timestamp)").update();
        String caseId = support.createHighRiskApproval("tenant", "customer", "BT-R1", "OD-R1");
        SessionAccount staff = new SessionAccount("staff", "tenant", "staff", "店铺客服",
                AccountRole.STORE_AGENT, "STORE-LIVING");
        support.claim(caseId, staff);
        when(stateMachine.status("BT-R1", "tenant")).thenReturn("WAITING_STORE_APPROVAL");
        when(commerce.refundApprovedTask("tenant", "customer", "BT-R1"))
                .thenReturn(new BigDecimal("10000.00"));

        var result = support.decide(caseId,
                new com.hanaki.ecom.domain.Domain.StaffDecisionRequest("APPROVE", "同意退款"), staff);

        assertThat(result.status()).isEqualTo("RESOLVED");
        assertThat(result.queueName()).isEqualTo("STORE");
        verify(stateMachine).transitionRequired("BT-R1", "tenant", "WAITING_STORE_APPROVAL", "APPROVED");
        verify(commerce).refundApprovedTask("tenant", "customer", "BT-R1");
        assertThat(db.sql("select count(*) from support_case where id=:id and queue_name='OFFICIAL'")
                .param("id", caseId).query(Integer.class).single()).isZero();
    }

    private String claimAfterStart(CountDownLatch start, String caseId, SessionAccount staff) throws Exception {
        start.await();
        return transactions.execute(status -> support.claim(caseId, staff).assigneeId());
    }

    private int countCases() {
        return db.sql("select count(*) from support_case").query(Integer.class).single();
    }
}
