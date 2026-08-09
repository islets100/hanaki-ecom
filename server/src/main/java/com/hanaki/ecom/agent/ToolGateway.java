package com.hanaki.ecom.agent;

import com.hanaki.ecom.commerce.CommerceService;
import com.hanaki.ecom.commerce.RefundRequestService;
import com.hanaki.ecom.domain.Domain.ConfirmResponse;
import com.hanaki.ecom.domain.Domain.ConfirmationStatus;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.RefundAssessmentView;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.support.SupportService;
import com.hanaki.ecom.security.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 所有业务工具的确定性安全网关。
 *
 * <p>语义风控只能提供风险信号；真正的安全边界在这里完成：Agent 工具白名单、可信 tenant/user、
 * 资源归属、用户确认内容摘要、有效期、幂等任务状态和审计记录。模型永远不会接收到 HMAC 密钥，
 * 也不能通过工具参数覆盖 tenantId/userId。</p>
 */
@Service
public class ToolGateway {
    /** 平台白名单是权限上限。租户配置只能与它求交集，不能注册这里没有的工具。 */
    private static final Map<Intent, Set<String>> TOOL_WHITELIST = Map.of(
            Intent.PRE_SALE, Set.of("query_product", "query_stock", "recommend_product"),
            Intent.IN_SALE, Set.of("query_order", "query_logistics", "urge_delivery"),
            Intent.AFTER_SALE, Set.of("query_order", "query_policy", "preview_refund", "submit_refund"),
            Intent.COMPLAINT, Set.of("query_order", "create_ticket"),
            Intent.HUMAN_SERVICE, Set.of("handoff_human"),
            Intent.UNKNOWN, Set.of());

    private final EcommerceStore store;
    private final SupportService support;
    private final BusinessTaskStateMachine stateMachine;
    private final RefundRequestService refunds;
    private final CommerceService commerce;
    private final JdbcClient db;
    private final byte[] secret;

    public ToolGateway(EcommerceStore store, SupportService support, BusinessTaskStateMachine stateMachine,
                       RefundRequestService refunds, CommerceService commerce, JdbcClient db,
                       @Value("${agent.security.confirm-secret}") String secret) {
        this.store = store;
        this.support = support;
        this.stateMachine = stateMachine;
        this.refunds = refunds;
        this.commerce = commerce;
        this.db = db;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void assertAllowed(Intent agent, String tool) {
        if (!TOOL_WHITELIST.getOrDefault(agent, Set.of()).contains(tool))
            throw new SecurityException("Agent " + agent + " 无权调用工具 " + tool);
    }

    /** 兼容旧调用；正式写操作必须使用包含金额和规则版本的重载。 */
    public String issueConfirmToken(String tenantId, String userId, String taskId,
                                    String orderId, String operation) {
        return issueConfirmToken(tenantId, userId, taskId, orderId, operation,
                BigDecimal.ZERO, "legacy-rule");
    }

    /**
     * 签发绑定具体操作内容的确认令牌。
     *
     * <p>不能只保存 confirmed=true。订单、操作类型、金额和规则版本先形成 operationDigest；nonce、
     * digest 和过期时间同时写入数据库并进入 HMAC 签名。用户确认后会重新计算摘要并与数据库记录
     * 逐项比对；任何金额、订单、退款方式或规则版本变化都必须重新签发令牌、再次确认。</p>
     */
    @Transactional
    public String issueConfirmToken(String tenantId, String userId, String taskId, String orderId,
                                    String operation, BigDecimal amount, String ruleVersion) {
        assertSafeClaim(tenantId, "tenantId");
        assertSafeClaim(userId, "userId");
        assertSafeClaim(taskId, "businessTaskId");
        assertSafeClaim(orderId, "orderId");
        assertSafeClaim(operation, "operationType");
        assertSafeClaim(ruleVersion, "ruleVersion");
        BigDecimal normalizedAmount = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        String digest = operationDigest(tenantId, userId, taskId, orderId,
                operation, normalizedAmount, ruleVersion);

        StoredConfirmation existing = db.sql("select nonce,expires_at,status from user_confirmation_record " +
                        "where tenant_id=:tenant and user_id=:user and business_task_id=:task " +
                        "and operation_digest=:digest order by created_at desc limit 1")
                .param("tenant", tenantId).param("user", userId).param("task", taskId)
                .param("digest", digest)
                .query((rs, row) -> new StoredConfirmation(rs.getString("nonce"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("status")))
                .optional().orElse(null);

        String nonce;
        Instant expiresAt;
        if (existing != null && ConfirmationStatus.PENDING.name().equals(existing.status())
                && existing.expiresAt().isAfter(Instant.now())) {
            nonce = existing.nonce();
            expiresAt = existing.expiresAt();
        } else {
            nonce = UUID.randomUUID().toString();
            expiresAt = Instant.now().plusSeconds(900);
            db.sql("insert into user_confirmation_record(id,tenant_id,user_id,business_task_id,order_id," +
                            "operation_type,amount,operation_digest,rule_version,nonce,status,expires_at,created_at) " +
                            "values(:id,:tenant,:user,:task,:orderId,:operation,:amount,:digest,:rule,:nonce," +
                            "'PENDING',:expires,current_timestamp)")
                    .param("id", "CONF-" + UUID.randomUUID()).param("tenant", tenantId).param("user", userId)
                    .param("task", taskId).param("orderId", orderId).param("operation", operation)
                    .param("amount", normalizedAmount).param("digest", digest).param("rule", ruleVersion)
                    .param("nonce", nonce).param("expires", java.sql.Timestamp.from(expiresAt)).update();
        }
        Claims claims = new Claims(tenantId, userId, taskId, orderId, operation,
                normalizedAmount, ruleVersion, digest, nonce, expiresAt.getEpochSecond());
        return sign(claims);
    }

    /**
     * 用户确认入口。身份来自已认证 Session，而不是令牌或请求体；令牌只能证明“此前展示过什么”，
     * 不能替代当前会话身份。重复确认通过状态机 CAS 和确认记录状态返回幂等结果，不会重复建审批单。
     */
    @Transactional
    public ConfirmResponse confirm(String token, String trustedTenantId, String trustedUserId, boolean confirmed) {
        Claims claims = verify(token);
        boolean trustedBusinessScope = claims.tenantId().equals(trustedTenantId)
                || TenantService.PLATFORM_TENANT_ID.equals(trustedTenantId);
        if (!trustedBusinessScope || !claims.userId().equals(trustedUserId))
            throw new SecurityException("确认令牌与当前认证上下文不一致");
        if (claims.expiresAt() < Instant.now().getEpochSecond()) {
            expire(claims);
            throw new IllegalArgumentException("确认已过期，请重新发起");
        }
        String recalculated = operationDigest(claims.tenantId(), claims.userId(), claims.taskId(),
                claims.orderId(), claims.operation(), claims.amount(), claims.ruleVersion());
        if (!MessageDigest.isEqual(recalculated.getBytes(StandardCharsets.UTF_8),
                claims.operationDigest().getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("确认内容摘要不一致，必须重新确认");

        ConfirmationRecord record = loadConfirmation(claims);
        verifyConfirmationRecord(record, claims);
        String businessTenantId = claims.tenantId();
        if (store.ownedOrder(businessTenantId, trustedUserId, claims.orderId()).isEmpty())
            throw new SecurityException("确认的订单不属于当前租户和用户");

        if (!confirmed) {
            db.sql("update user_confirmation_record set status='CANCELLED',confirmed_at=current_timestamp " +
                            "where tenant_id=:tenant and user_id=:user and business_task_id=:task and nonce=:nonce " +
                            "and status='PENDING'")
                    .param("tenant", businessTenantId).param("user", trustedUserId)
                    .param("task", claims.taskId()).param("nonce", claims.nonce()).update();
            stateMachine.tryTransition(claims.taskId(), businessTenantId,
                    "WAITING_CONFIRMATION", "CANCELLED");
            return new ConfirmResponse(claims.taskId(), "CANCELLED", "操作已取消，没有产生业务写入。",
                    "confirm-" + UUID.randomUUID(), null);
        }

        assertAllowed(Intent.AFTER_SALE, claims.operation());
        db.sql("update user_confirmation_record set status='CONFIRMED',confirmed_at=current_timestamp " +
                        "where tenant_id=:tenant and user_id=:user and business_task_id=:task and nonce=:nonce " +
                        "and status='PENDING'")
                .param("tenant", businessTenantId).param("user", trustedUserId)
                .param("task", claims.taskId()).param("nonce", claims.nonce()).update();
        RefundAssessmentView assessment = refunds.find(claims.taskId(), businessTenantId, trustedUserId)
                .orElse(null);
        boolean automatic = assessment != null
                && refunds.automaticRefundAllowed(claims.taskId(), businessTenantId, trustedUserId);
        if (automatic) {
            boolean changed = stateMachine.tryTransition(claims.taskId(), businessTenantId,
                    "WAITING_CONFIRMATION", "APPROVED");
            if (changed) {
                BigDecimal balanceAfter = commerce.refundApprovedTask(
                        businessTenantId, trustedUserId, claims.taskId());
                return new ConfirmResponse(claims.taskId(), "REFUNDED",
                        "退款理由充分度为 " + assessment.score() + " 分，已按知识库规则自动退款，无需人工审核。当前余额 ¥"
                                + balanceAfter.toPlainString(),
                        "confirm-" + UUID.randomUUID(), null);
            }
            String status = stateMachine.status(claims.taskId(), businessTenantId);
            return new ConfirmResponse(claims.taskId(), "REFUNDED".equals(status) ? "REFUNDED" : "ALREADY_PROCESSED",
                    "该任务已处理，本次重复确认没有再次退款。当前状态：" + status + "。",
                    "confirm-" + UUID.randomUUID(), null);
        }

        boolean changed = stateMachine.tryTransition(claims.taskId(), businessTenantId,
                "WAITING_CONFIRMATION", "WAITING_STORE_APPROVAL");
        String caseId = changed ? support.createHighRiskApproval(businessTenantId, trustedUserId,
                claims.taskId(), claims.orderId()) : null;
        String message = changed
                ? "退款申请已提交给订单所属店铺审核；店铺客服同意后会直接退款，不再转商城官方客服复审。"
                : "该任务已处理，本次重复确认没有再次提交。";
        return new ConfirmResponse(claims.taskId(), changed ? "WAITING_STORE_APPROVAL" : "ALREADY_PROCESSED",
                message, "confirm-" + UUID.randomUUID(), caseId);
    }

    private ConfirmationRecord loadConfirmation(Claims claims) {
        return db.sql("select order_id,operation_type,amount,operation_digest,rule_version,status,expires_at " +
                        "from user_confirmation_record where tenant_id=:tenant and user_id=:user " +
                        "and business_task_id=:task and nonce=:nonce")
                .param("tenant", claims.tenantId()).param("user", claims.userId())
                .param("task", claims.taskId()).param("nonce", claims.nonce())
                .query((rs, row) -> new ConfirmationRecord(rs.getString("order_id"),
                        rs.getString("operation_type"), rs.getBigDecimal("amount"),
                        rs.getString("operation_digest"), rs.getString("rule_version"),
                        rs.getString("status"), rs.getTimestamp("expires_at").toInstant()))
                .optional().orElseThrow(() -> new SecurityException("确认记录不存在或不属于当前用户"));
    }

    private void verifyConfirmationRecord(ConfirmationRecord record, Claims claims) {
        boolean sameAmount = record.amount().compareTo(claims.amount()) == 0;
        if (!record.orderId().equals(claims.orderId()) || !record.operation().equals(claims.operation())
                || !record.operationDigest().equals(claims.operationDigest())
                || !record.ruleVersion().equals(claims.ruleVersion()) || !sameAmount)
            throw new SecurityException("确认记录与最终执行参数不一致，必须重新确认");
        if (ConfirmationStatus.CANCELLED.name().equals(record.status())
                || ConfirmationStatus.EXPIRED.name().equals(record.status()))
            throw new IllegalArgumentException("确认已经取消或过期，请重新发起");
    }

    private void expire(Claims claims) {
        db.sql("update user_confirmation_record set status='EXPIRED' where tenant_id=:tenant and user_id=:user " +
                        "and business_task_id=:task and nonce=:nonce and status='PENDING'")
                .param("tenant", claims.tenantId()).param("user", claims.userId())
                .param("task", claims.taskId()).param("nonce", claims.nonce()).update();
    }

    static String operationDigest(String tenantId, String userId, String taskId, String orderId,
                                  String operation, BigDecimal amount, String ruleVersion) {
        String canonical = String.join("\n", tenantId, userId, taskId, orderId, operation,
                amount.stripTrailingZeros().toPlainString(), ruleVersion);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算操作内容摘要", error);
        }
    }

    private String sign(Claims claims) {
        String payload = String.join("|", claims.tenantId(), claims.userId(), claims.taskId(), claims.orderId(),
                claims.operation(), claims.amount().toPlainString(), claims.ruleVersion(),
                claims.operationDigest(), claims.nonce(), String.valueOf(claims.expiresAt()));
        return encode(payload) + "." + encode(hmac(payload));
    }

    private Claims verify(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("缺少确认令牌，请重新发起操作");
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) throw new IllegalArgumentException("确认令牌段数无效");
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!constantTimeEquals(hmac(payload), Base64.getUrlDecoder().decode(parts[1])))
                throw new SecurityException("确认令牌签名无效");
            String[] values = payload.split("\\|", -1);
            if (values.length != 10) throw new IllegalArgumentException("确认令牌字段数无效");
            return new Claims(values[0], values[1], values[2], values[3], values[4],
                    new BigDecimal(values[5]), values[6], values[7], values[8], Long.parseLong(values[9]));
        } catch (SecurityException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("确认令牌格式无效", error);
        }
    }

    private void assertSafeClaim(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,160}"))
            throw new IllegalArgumentException(field + " 格式无效");
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成确认签名", error);
        }
    }

    private String encode(String value) { return encode(value.getBytes(StandardCharsets.UTF_8)); }
    private String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private boolean constantTimeEquals(byte[] left, byte[] right) { return MessageDigest.isEqual(left, right); }

    private record Claims(String tenantId, String userId, String taskId, String orderId, String operation,
                          BigDecimal amount, String ruleVersion, String operationDigest,
                          String nonce, long expiresAt) {}
    private record StoredConfirmation(String nonce, Instant expiresAt, String status) {}
    private record ConfirmationRecord(String orderId, String operation, BigDecimal amount,
                                      String operationDigest, String ruleVersion,
                                      String status, Instant expiresAt) {}
}
