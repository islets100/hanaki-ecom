package com.hanaki.ecom.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.agent.AiModelGateway;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.domain.Domain.RefundReasonScore;
import com.hanaki.ecom.store.EcommerceStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundRequestServiceTest {
    @TempDir Path evidenceRoot;
    private JdbcClient db;
    private AiModelGateway model;
    private RefundRequestService refunds;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:refund-reason-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        db.sql("insert into knowledge_doc(id,tenant_id,domain,title,content,version,active) values(" +
                "'K-AFTER-1','tenant-a','AFTER_SALE','退款规则','商品存在明确质量问题且证据完整时可以退款','v1',true)").update();
        db.sql("insert into knowledge_doc(id,tenant_id,domain,title,content,version,active) values(" +
                "'K-CANCEL-01','tenant-a','AFTER_SALE','未发货取消规则','余额支付订单在实际发货前可以取消并全额退款','v1',true)").update();
        db.sql("insert into order_fulfillment(order_id,tenant_id,store_id,planned_ship_at," +
                        "estimated_arrival_at,status,updated_at) values('OD-1','tenant-a','STORE-1'," +
                        "current_timestamp,dateadd('DAY',3,current_timestamp),'PLANNED',current_timestamp)")
                .update();
        model = mock(AiModelGateway.class);
        refunds = new RefundRequestService(db, new EcommerceStore(db), model, new ObjectMapper(),
                evidenceRoot.toString(), 4, 10 * 1024 * 1024, 100 * 1024 * 1024, 80, "qwen-plus");
    }

    @Test
    void textImageAndVideoCanProduceAutomaticRefundDecision() {
        insertTask("BT-HIGH");
        when(model.scoreRefundReason(any())).thenReturn(new RefundReasonScore(92, true,
                "文字说明符合质量问题退款规则", List.of("K-AFTER-1"), List.of()));

        var result = refunds.assess("BT-HIGH", "tenant-a", "user-a", order(),
                "收到商品后发现无法开机，申请质量问题退款",
                List.of(png("failure.png"), mp4("failure.mp4")));

        assertThat(result.score()).isEqualTo(92);
        assertThat(result.decisionMode()).isEqualTo("AUTO_REFUND");
        assertThat(result.evidence()).extracting(item -> item.mediaType())
                .containsExactly("IMAGE", "VIDEO");
        assertThat(refunds.automaticRefundAllowed("BT-HIGH", "tenant-a", "user-a")).isTrue();
        assertThat(db.sql("select count(*) from refund_evidence where business_task_id='BT-HIGH'")
                .query(Integer.class).single()).isEqualTo(2);
        ArgumentCaptor<com.hanaki.ecom.domain.Domain.RefundReasonAssessmentRequest> request =
                ArgumentCaptor.forClass(com.hanaki.ecom.domain.Domain.RefundReasonAssessmentRequest.class);
        verify(model).scoreRefundReason(request.capture());
        assertThat(request.getValue().paymentStatus()).isEqualTo("BALANCE_PAID");
        assertThat(request.getValue().logisticsStatus()).isEqualTo("待发货");
    }

    @Test
    void mediaOnlyRequestAlwaysRequiresStoreReview() {
        insertTask("BT-MEDIA");
        when(model.scoreRefundReason(any())).thenReturn(new RefundReasonScore(99, true,
                "媒体需要人工确认", List.of("K-AFTER-1"), List.of()));

        var result = refunds.assess("BT-MEDIA", "tenant-a", "user-a", order(), "",
                List.of(png("proof.png")));

        assertThat(result.score()).isEqualTo(80);
        assertThat(result.decisionMode()).isEqualTo("STORE_REVIEW");
        assertThat(refunds.automaticRefundAllowed("BT-MEDIA", "tenant-a", "user-a")).isFalse();
    }

    @Test
    void explicitUnshippedCancellationGetsDeterministicAutomaticScore() {
        insertTask("BT-CANCEL");
        when(model.scoreRefundReason(any())).thenReturn(new RefundReasonScore(40, false,
                "模型认为理由信息不足", List.of(), List.of("缺少取消资格信息")));

        var result = refunds.assess("BT-CANCEL", "tenant-a", "user-a", order(),
                "订单还没有发货，我不想要了，申请取消订单并全额退款", List.of());

        assertThat(result.score()).isEqualTo(90);
        assertThat(result.policyEligible()).isTrue();
        assertThat(result.decisionMode()).isEqualTo("AUTO_REFUND");
        assertThat(result.matchedRuleIds()).contains("K-CANCEL-01");
        assertThat(result.missingInformation()).isEmpty();
    }

    @Test
    void modelFailureFailsClosedToStoreReview() {
        insertTask("BT-FALLBACK");
        when(model.scoreRefundReason(any())).thenThrow(new IllegalStateException("model unavailable"));

        var result = refunds.assess("BT-FALLBACK", "tenant-a", "user-a", order(),
                "商品有问题", List.of());

        assertThat(result.score()).isZero();
        assertThat(result.decisionMode()).isEqualTo("STORE_REVIEW");
        assertThat(result.summary()).contains("店铺人工审核");
    }

    @Test
    void deliveredMoreThanSevenDaysAgoCannotBeAutomaticallyRefunded() {
        insertTask("BT-OLD-DELIVERY");
        db.sql("update order_fulfillment set planned_ship_at=dateadd('DAY',-12,current_timestamp)," +
                        "estimated_arrival_at=dateadd('DAY',-10,current_timestamp)," +
                        "delivered_at=dateadd('DAY',-8,current_timestamp),status='DELIVERED'," +
                        "updated_at=current_timestamp where order_id='OD-1' and tenant_id='tenant-a'")
                .update();
        when(model.scoreRefundReason(any())).thenReturn(new RefundReasonScore(95, true,
                "理由符合已发布规则", List.of("K-AFTER-1"), List.of()));

        var result = refunds.assess("BT-OLD-DELIVERY", "tenant-a", "user-a", order(),
                "商品存在质量问题，申请退款", List.of());

        assertThat(result.decisionMode()).isEqualTo("STORE_REVIEW");
        assertThat(result.missingInformation()).anyMatch(item -> item.contains("超过签收后 7 个自然日"));
        assertThat(refunds.automaticRefundAllowed("BT-OLD-DELIVERY", "tenant-a", "user-a")).isFalse();
    }

    @Test
    void shippedButNotDeliveredOrderRequiresStoreReview() {
        insertTask("BT-IN-TRANSIT");
        db.sql("update order_fulfillment set shipped_at=current_timestamp,status='SHIPPED'," +
                        "updated_at=current_timestamp where order_id='OD-1' and tenant_id='tenant-a'")
                .update();
        when(model.scoreRefundReason(any())).thenReturn(new RefundReasonScore(96, true,
                "理由符合未发货取消规则", List.of("K-CANCEL-01"), List.of()));

        var result = refunds.assess("BT-IN-TRANSIT", "tenant-a", "user-a", order(),
                "订单尚未发货，申请取消订单并退款", List.of());

        assertThat(result.decisionMode()).isEqualTo("STORE_REVIEW");
        assertThat(result.missingInformation()).anyMatch(item -> item.contains("已经发货但尚未签收"));
        assertThat(refunds.automaticRefundAllowed("BT-IN-TRANSIT", "tenant-a", "user-a")).isFalse();
    }

    @Test
    void inTransitChangeOfMindCannotScoreAboveManualReviewThreshold() {
        insertTask("BT-IN-TRANSIT-CHANGE-MIND");
        db.sql("update order_fulfillment set shipped_at=current_timestamp,status='SHIPPED'," +
                        "updated_at=current_timestamp where order_id='OD-1' and tenant_id='tenant-a'")
                .update();
        when(model.scoreRefundReason(any())).thenReturn(new RefundReasonScore(96, true,
                "模型误判命中未发货取消规则", List.of("K-CANCEL-01"), List.of()));

        var result = refunds.assess("BT-IN-TRANSIT-CHANGE-MIND", "tenant-a", "user-a", order(),
                "不想要了", List.of());

        assertThat(result.score()).isEqualTo(60);
        assertThat(result.policyEligible()).isFalse();
        assertThat(result.decisionMode()).isEqualTo("STORE_REVIEW");
        assertThat(result.matchedRuleIds()).doesNotContain("K-CANCEL-01");
    }

    @Test
    void missingFulfillmentFactFailsClosedToStoreReview() {
        insertTask("BT-NO-FULFILLMENT");
        db.sql("delete from order_fulfillment where order_id='OD-1' and tenant_id='tenant-a'").update();
        when(model.scoreRefundReason(any())).thenReturn(new RefundReasonScore(93, true,
                "理由符合已发布规则", List.of("K-AFTER-1"), List.of()));

        var result = refunds.assess("BT-NO-FULFILLMENT", "tenant-a", "user-a", order(),
                "商品存在明确质量问题", List.of());

        assertThat(result.decisionMode()).isEqualTo("STORE_REVIEW");
        assertThat(result.missingInformation()).contains("缺少权威履约记录，需人工核验退款资格");
    }

    @Test
    void declaredMediaTypeMustMatchFileContent() {
        insertTask("BT-INVALID");
        MockMultipartFile forged = new MockMultipartFile("evidence", "fake.png", "image/png",
                "not really a png".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> refunds.assess("BT-INVALID", "tenant-a", "user-a", order(),
                "商品有问题", List.of(forged)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体格式不一致");
        assertThat(db.sql("select count(*) from refund_assessment where business_task_id='BT-INVALID'")
                .query(Integer.class).single()).isZero();
    }

    private void insertTask(String id) {
        db.sql("insert into business_task(id,tenant_id,user_id,order_id,type,status,rule_version,version," +
                        "created_at,updated_at) values(:id,'tenant-a','user-a','OD-1','REFUND'," +
                        "'WAITING_CONFIRMATION','v1',0,current_timestamp,current_timestamp)")
                .param("id", id).update();
    }

    private OrderSummary order() {
        Instant now = Instant.now();
        return new OrderSummary("OD-1", "****OD-1", "tenant-a", "user-a", "P-1",
                "测试商品", "标准款", new BigDecimal("199"), "PROCESSING", "BALANCE_PAID",
                "待发货", "测试店铺", now.plusSeconds(3600), now.plusSeconds(7200), now);
    }

    private MockMultipartFile png(String name) {
        return new MockMultipartFile("evidence", name, "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0});
    }

    private MockMultipartFile mp4(String name) {
        return new MockMultipartFile("evidence", name, "video/mp4",
                new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0});
    }
}
