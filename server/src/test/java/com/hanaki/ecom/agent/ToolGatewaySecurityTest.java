package com.hanaki.ecom.agent;

import com.hanaki.ecom.commerce.CommerceService;
import com.hanaki.ecom.commerce.RefundRequestService;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.RefundAssessmentView;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.support.SupportService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ToolGateway 的测试只验证确定性安全边界，不依赖模型输出或 Prompt。 */
class ToolGatewaySecurityTest {
    private JdbcClient db;
    private BusinessTaskStateMachine stateMachine;
    private SupportService support;
    private RefundRequestService refunds;
    private CommerceService commerce;
    private ToolGateway gateway;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:tool-gateway-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        db.sql("insert into merchant_store(id,tenant_id,name,created_at) " +
                "values('STORE-1','tenant-a','测试店铺',current_timestamp)").update();
        db.sql("insert into product(id,tenant_id,store_id,name,stock) " +
                "values('P1','tenant-a','STORE-1','测试商品',1)").update();
        db.sql("insert into customer_order(id,tenant_id,user_id,product_id,amount,status,payment_status," +
                        "logistics_status,created_at) values('OD12345678','tenant-a','user-a','P1',20," +
                        "'PAID','BALANCE_PAID','待发货',current_timestamp)")
                .update();
        stateMachine = mock(BusinessTaskStateMachine.class);
        support = mock(SupportService.class);
        refunds = mock(RefundRequestService.class);
        commerce = mock(CommerceService.class);
        gateway = new ToolGateway(new EcommerceStore(db), support, stateMachine, refunds, commerce, db,
                "test-secret-with-sufficient-entropy");
    }

    @Test
    void preSaleAgentCannotObtainRefundTool() {
        assertThatThrownBy(() -> gateway.assertAllowed(Intent.PRE_SALE, "submit_refund"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("无权调用工具");
    }

    @Test
    void confirmationCannotBeReplayedByAnotherAuthenticatedUser() {
        String token = gateway.issueConfirmToken("tenant-a", "user-a", "BT-1", "OD12345678",
                "submit_refund", new BigDecimal("20.00"), "after-sale-v12");

        assertThatThrownBy(() -> gateway.confirm(token, "tenant-a", "user-b", true))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("认证上下文不一致");
    }

    @Test
    void changedAmountInvalidatesPreviouslyIssuedConfirmation() {
        String token = gateway.issueConfirmToken("tenant-a", "user-a", "BT-2", "OD12345678",
                "submit_refund", new BigDecimal("20.00"), "after-sale-v12");
        // 模拟确认后、执行前参数被错误代码改写；数据库记录与签名令牌不再一致，必须 fail closed。
        db.sql("update user_confirmation_record set amount=50 where business_task_id='BT-2'").update();

        assertThatThrownBy(() -> gateway.confirm(token, "tenant-a", "user-a", true))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("最终执行参数不一致");
    }

    @Test
    void duplicateConfirmationDoesNotCreateSecondApproval() {
        String token = gateway.issueConfirmToken("tenant-a", "user-a", "BT-3", "OD12345678",
                "submit_refund", new BigDecimal("20.00"), "after-sale-v12");
        when(stateMachine.tryTransition("BT-3", "tenant-a",
                "WAITING_CONFIRMATION", "WAITING_STORE_APPROVAL")).thenReturn(true, false);
        when(support.createHighRiskApproval("tenant-a", "user-a", "BT-3", "OD12345678"))
                .thenReturn("CASE-1");

        var first = gateway.confirm(token, "tenant-a", "user-a", true);
        var duplicate = gateway.confirm(token, "tenant-a", "user-a", true);

        assertThat(first.status()).isEqualTo("WAITING_STORE_APPROVAL");
        assertThat(duplicate.status()).isEqualTo("ALREADY_PROCESSED");
        verify(support).createHighRiskApproval("tenant-a", "user-a", "BT-3", "OD12345678");
    }

    @Test
    void platformCustomerCanConfirmAnOrderInItsServerResolvedMerchantScope() {
        String token = gateway.issueConfirmToken("tenant-a", "user-a", "BT-4", "OD12345678",
                "submit_refund", new BigDecimal("20.00"), "after-sale-v12");
        when(stateMachine.tryTransition("BT-4", "tenant-a",
                "WAITING_CONFIRMATION", "WAITING_STORE_APPROVAL")).thenReturn(true);
        when(support.createHighRiskApproval("tenant-a", "user-a", "BT-4", "OD12345678"))
                .thenReturn("CASE-4");

        var response = gateway.confirm(token, "platform", "user-a", true);

        assertThat(response.status()).isEqualTo("WAITING_STORE_APPROVAL");
        verify(support).createHighRiskApproval("tenant-a", "user-a", "BT-4", "OD12345678");
    }

    @Test
    void scoreAboveEightyRefundsImmediatelyAfterConfirmation() {
        String token = gateway.issueConfirmToken("tenant-a", "user-a", "BT-5", "OD12345678",
                "submit_refund", new BigDecimal("20.00"), "refund-rules-1");
        RefundAssessmentView assessment = new RefundAssessmentView("BT-5", "OD12345678", "商品存在规则明确的问题",
                91, true, "AUTO_REFUND", "符合无条件退款规则", List.of("K-1"), List.of(),
                "refund-rules-1", "qwen-plus", List.of(), Instant.now());
        when(refunds.find("BT-5", "tenant-a", "user-a")).thenReturn(Optional.of(assessment));
        when(refunds.automaticRefundAllowed("BT-5", "tenant-a", "user-a")).thenReturn(true);
        when(stateMachine.tryTransition("BT-5", "tenant-a", "WAITING_CONFIRMATION", "APPROVED"))
                .thenReturn(true);
        when(commerce.refundApprovedTask("tenant-a", "user-a", "BT-5"))
                .thenReturn(new BigDecimal("100.00"));

        var response = gateway.confirm(token, "platform", "user-a", true);

        assertThat(response.status()).isEqualTo("REFUNDED");
        assertThat(response.message()).contains("91 分", "自动退款", "100.00");
    }
}
