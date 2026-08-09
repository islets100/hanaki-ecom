package com.hanaki.ecom.support;

import com.hanaki.ecom.commerce.CommerceService;
import com.hanaki.ecom.agent.BusinessTaskStateMachine;
import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.StaffDecisionRequest;
import com.hanaki.ecom.domain.Domain.SupportCaseView;
import com.hanaki.ecom.domain.Domain.SupportMessageView;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import com.hanaki.ecom.security.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** 店铺客服和商城官方客服共用的人工会话服务，所有可见范围都在 SQL 条件中强制执行。 */
@Service
public class SupportService {
    public static final String DEFAULT_STORE = "STORE-HANAKI";
    private final JdbcClient db;
    private final CommerceService commerce;
    private final BusinessTaskStateMachine stateMachine;

    public SupportService(JdbcClient db, CommerceService commerce, BusinessTaskStateMachine stateMachine) {
        this.db = db;
        this.commerce = commerce;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public String createHandoff(String tenantId, String customerId, String conversationId,
                                String summary, boolean complaint) {
        RecentOrder recent = recentOrders(tenantId, customerId).stream().findFirst().orElse(null);
        // 队列归属以服务端保存的会话类型为准，不能只靠用户文字中的关键词猜测。
        // 官方 AI 中说“转人工”只进入官方队列，店铺 AI 中说“转人工”只回到原店铺。
        boolean officialQueue = complaint || isOfficialAiConversation(tenantId, customerId, conversationId);
        String caseType = officialQueue ? "COMPLAINT" : "HUMAN_HANDOFF";
        /*
         * Graph 可能在“工单已创建、Checkpoint 尚未落盘”之间恢复并再次进入 human_handoff。
         * 因此人工接管本身也必须幂等：同一租户、用户、会话和类型若已有未结束工单，直接复用，
         * 不能把 Graph 可恢复误解成外部写操作天然 exactly-once。
         */
        String existing = db.sql("select id from support_case where tenant_id=:tenant and customer_id=:customer " +
                        "and conversation_id=:conversation and type=:type and status<>'RESOLVED' " +
                        "order by created_at desc limit 1")
                .param("tenant", tenantId).param("customer", customerId)
                .param("conversation", conversationId).param("type", caseType)
                .query(String.class).optional().orElse(null);
        if (existing != null) return existing;
        // 从某个商品发起的店铺 AI 会话转人工时，必须回到该商品所属店铺，不能误投到最近订单店铺。
        String storeId = storeAiSession(tenantId, customerId, conversationId)
                .map(StoreAiSession::storeId)
                .orElse(recent == null ? DEFAULT_STORE : recent.storeId());
        String caseId = createCase(tenantId, customerId, storeId, conversationId, null, null,
                caseType, officialQueue ? "OFFICIAL" : "STORE",
                summary, officialQueue ? "HIGH" : "MEDIUM");
        if (officialQueue) addMessage(caseId, "OFFICIAL-ORDER-ASSISTANT", AccountRole.OFFICIAL_AGENT.name(),
                officialOrderQuestion(tenantId, customerId));
        return caseId;
    }

    /**
     * 商品详情页只创建店铺智能客服上下文，不创建人工工单。
     * 之后每条消息都走 Agent 主路由；只有路由结果为 COMPLAINT/转人工时才调用 createHandoff。
     */
    @Transactional
    public StoreAiSession startStoreAi(String tenantId, String customerId, String productId) {
        StoreProduct product = findStoreProduct(tenantId, productId);
        String conversationId = "store-ai-" + UUID.randomUUID();
        db.sql("insert into store_ai_session(conversation_id,tenant_id,user_id,store_id,product_id,store_name," +
                        "product_name,created_at,updated_at) values(:conversation,:tenant,:user,:store,:product," +
                        ":storeName,:productName,current_timestamp,current_timestamp)")
                .param("conversation", conversationId).param("tenant", product.tenantId()).param("user", customerId)
                .param("store", product.storeId()).param("product", productId)
                .param("storeName", product.storeName()).param("productName", product.productName()).update();
        String greeting = "你好，我是「" + product.storeName() + "」智能客服，当前正在为你解答「" +
                product.productName() + "」相关问题。每条消息都会先进行意图理解；如需真人服务，请直接说“转人工”。";
        return new StoreAiSession(conversationId, product.tenantId(), product.storeId(), product.storeName(), productId,
                product.productName(), greeting);
    }

    /** 每次 Agent 运行都重新装配可信店铺上下文，避免多轮对话后上下文被窗口裁剪。 */
    public Optional<String> storeAiContext(String tenantId, String customerId, String conversationId) {
        return storeAiSession(tenantId, customerId, conversationId).map(session ->
                "STORE_CONTEXT: 当前是店铺智能客服会话；店铺=" + session.storeName() +
                        "；storeId=" + session.storeId() + "；咨询商品=" + session.productName() +
                        "；productId=" + session.productId() + "。普通问题由智能客服回答，仅当用户意图为转人工时进入人工队列。");
    }

    /**
     * 页面右上角的官方客服入口只建立官方 AI 会话，不提前创建人工工单。
     * 欢迎语基于当前登录用户的真实近期订单生成，浏览器不能伪造订单上下文。
     */
    @Transactional
    public OfficialAiSession startOfficialAi(String tenantId, String customerId) {
        String conversationId = "official-ai-" + UUID.randomUUID();
        db.sql("insert into official_ai_session(conversation_id,tenant_id,user_id,created_at,updated_at) " +
                        "values(:conversation,:tenant,:user,current_timestamp,current_timestamp)")
                .param("conversation", conversationId).param("tenant", tenantId).param("user", customerId)
                .update();
        String greeting = officialOrderQuestion(tenantId, customerId)
                + " 我还可以查询这些订单的物流，并调阅相关店铺智能客服和人工客服聊天记录。"
                + " 默认由官方智能客服为你处理；需要真人时请直接说“转人工”。";
        return new OfficialAiSession(conversationId, greeting);
    }

    /**
     * 官方 AI 每一轮都重新从数据库组装快照，包含近期订单、物流、店铺 AI 与人工记录。
     * 所有查询都带 tenantId + userId 约束，只允许看到当前登录用户自己的数据。
     */
    public Optional<String> officialAiContext(String tenantId, String customerId, String conversationId) {
        if (!isOfficialAiConversation(tenantId, customerId, conversationId)) return Optional.empty();

        List<OfficialOrderContext> orders = db.sql("select o.id,o.tenant_id,p.name product_name,p.store_id,s.name store_name," +
                        "o.status,o.payment_status,o.logistics_status,f.planned_ship_at,f.estimated_arrival_at " +
                        "from customer_order o join product p on p.id=o.product_id and p.tenant_id=o.tenant_id " +
                        "left join merchant_store s on s.id=p.store_id and s.tenant_id=p.tenant_id " +
                        "left join order_fulfillment f on f.order_id=o.id and f.tenant_id=o.tenant_id " +
                        "where o.user_id=:user order by o.created_at desc limit 5")
                .param("user", customerId)
                .query((rs, n) -> new OfficialOrderContext(rs.getString("id"), rs.getString("tenant_id"), rs.getString("product_name"),
                        rs.getString("store_id"), rs.getString("store_name"), rs.getString("status"),
                        rs.getString("payment_status"), rs.getString("logistics_status"),
                        rs.getTimestamp("planned_ship_at") == null ? null : rs.getTimestamp("planned_ship_at").toInstant().toString(),
                        rs.getTimestamp("estimated_arrival_at") == null ? null : rs.getTimestamp("estimated_arrival_at").toInstant().toString()))
                .list();

        StringBuilder snapshot = new StringBuilder("OFFICIAL_CONTEXT: 当前是商城官方智能客服会话。")
                .append("普通咨询由官方 AI 回答；只有识别到转人工或需人工处理的投诉时才进入官方人工队列。\n")
                .append("近期订单与物流：\n");
        if (orders.isEmpty()) snapshot.append("- 当前用户没有近期订单。\n");
        for (OfficialOrderContext order : orders) {
            snapshot.append("- 订单=").append(order.id()).append("；商品=").append(order.productName())
                    .append("；店铺=").append(order.storeName()).append("(").append(order.storeId()).append(")")
                    .append("；订单状态=").append(order.status()).append("；支付=").append(order.paymentStatus())
                    .append("；物流状态=").append(order.logisticsStatus())
                    .append("；计划发货=").append(order.plannedShipAt()).append("；预计到达=").append(order.estimatedArrivalAt())
                    .append("；物流轨迹=").append(logisticsSummary(order.tenantId(), order.id())).append("\n");
        }

        List<String> storeAiHistory = db.sql("select s.store_name,s.product_name,m.role,m.content from store_ai_session s " +
                        "join conversation_message m on m.tenant_id=s.tenant_id and m.user_id=s.user_id " +
                        "and m.conversation_id=s.conversation_id where s.user_id=:user " +
                        "order by m.created_at desc limit 20")
                .param("user", customerId)
                .query((rs, n) -> "- 店铺=" + rs.getString("store_name") + "；商品=" + rs.getString("product_name")
                        + "；角色=" + rs.getString("role") + "；内容=" + clip(rs.getString("content"), 300)).list();
        snapshot.append("店铺智能客服聊天记录：\n")
                .append(storeAiHistory.isEmpty() ? "- 暂无。\n" : String.join("\n", storeAiHistory) + "\n");

        List<String> humanHistory = db.sql("select c.id,c.queue_name,c.store_id,c.order_id,c.status,m.sender_role,m.content " +
                        "from support_case c left join support_message m on m.case_id=c.id " +
                        "where c.customer_id=:user order by c.updated_at desc,m.created_at desc limit 30")
                .param("user", customerId)
                .query((rs, n) -> "- 工单=" + rs.getString("id") + "；队列=" + rs.getString("queue_name")
                        + "；店铺=" + rs.getString("store_id") + "；订单=" + rs.getString("order_id")
                        + "；状态=" + rs.getString("status") + "；角色=" + rs.getString("sender_role")
                        + "；内容=" + clip(rs.getString("content"), 300)).list();
        snapshot.append("店铺/官方人工客服聊天记录：\n")
                .append(humanHistory.isEmpty() ? "- 暂无。" : String.join("\n", humanHistory));
        return Optional.of(snapshot.toString());
    }

    /** Agent 用服务端会话类型生成正确的官方/店铺转人工提示。 */
    public boolean isOfficialAiConversation(String tenantId, String customerId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        return db.sql("select count(*) from official_ai_session where tenant_id=:tenant and user_id=:user " +
                        "and conversation_id=:conversation")
                .param("tenant", tenantId).param("user", customerId).param("conversation", conversationId)
                .query(Integer.class).single() == 1;
    }

    private String logisticsSummary(String tenantId, String orderId) {
        List<String> events = db.sql("select event_time,location,description from logistics_event " +
                        "where tenant_id=:tenant and order_id=:order order by event_time desc limit 5")
                .param("tenant", tenantId).param("order", orderId)
                .query((rs, n) -> rs.getTimestamp("event_time").toInstant() + " "
                        + clip(rs.getString("location"), 80) + " " + clip(rs.getString("description"), 180)).list();
        return events.isEmpty() ? "暂无物流节点" : String.join(" | ", events);
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.isBlank()) return "（无）";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    /** 商品详情页直接联系店铺时，工单归属由商品的可信 store_id 决定。 */
    @Transactional
    public String createStoreContact(String tenantId, String customerId, String productId, String message) {
        StoreProduct product = findStoreProduct(tenantId, productId);
        String summary = message == null || message.isBlank()
                ? "咨询「" + product.productName() + "」" : message.strip();
        String caseId = createCase(product.tenantId(), customerId, product.storeId(), null, null, null,
                "HUMAN_HANDOFF", "STORE", summary, "MEDIUM");
        addMessage(caseId, "STORE-ROUTER", AccountRole.STORE_AGENT.name(),
                "已为你联系「" + product.storeName() + "」客服，请补充需要咨询的问题。");
        return caseId;
    }

    private StoreProduct findStoreProduct(String tenantId, String productId) {
        String tenantPredicate = TenantService.PLATFORM_TENANT_ID.equals(tenantId)
                ? "" : "and p.tenant_id=:tenant ";
        var query = db.sql("select p.tenant_id,p.store_id,p.name,s.name store_name from product p " +
                        "join merchant_store s on s.id=p.store_id and s.tenant_id=p.tenant_id " +
                        "join saas_tenant t on t.tenant_id=p.tenant_id and t.tenant_type='MERCHANT' and t.status='ACTIVE' " +
                        "where p.id=:product " + tenantPredicate)
                .param("product", productId);
        if (!TenantService.PLATFORM_TENANT_ID.equals(tenantId)) query = query.param("tenant", tenantId);
        return query.query((rs, n) -> new StoreProduct(rs.getString("tenant_id"), rs.getString("store_id"), rs.getString("name"),
                        rs.getString("store_name"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("商品或店铺不存在"));
    }

    /** 返回服务端保存的店铺/商品会话范围，供 Agent 作为不可变业务身份使用。 */
    public Optional<StoreAiSession> storeAiSession(String tenantId, String customerId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        return db.sql("select conversation_id,tenant_id,store_id,store_name,product_id,product_name from store_ai_session " +
                        "where user_id=:user and conversation_id=:conversation " +
                        "and (:tenant='platform' or tenant_id=:tenant)")
                .param("tenant", tenantId).param("user", customerId).param("conversation", conversationId)
                .query((rs, n) -> new StoreAiSession(rs.getString("conversation_id"), rs.getString("tenant_id"), rs.getString("store_id"),
                        rs.getString("store_name"), rs.getString("product_id"), rs.getString("product_name"), null))
                .optional();
    }

    /** 客户身份属于平台；具体会话的数据上下文由服务端保存的会话类型决定。 */
    public Optional<String> conversationTenant(String customerId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        Optional<String> merchant = db.sql("select tenant_id from store_ai_session " +
                        "where user_id=:user and conversation_id=:conversation")
                .param("user", customerId).param("conversation", conversationId)
                .query(String.class).optional();
        if (merchant.isPresent()) return merchant;
        int official = db.sql("select count(*) from official_ai_session " +
                        "where tenant_id='platform' and user_id=:user and conversation_id=:conversation")
                .param("user", customerId).param("conversation", conversationId)
                .query(Integer.class).single();
        return official == 1 ? Optional.of(TenantService.PLATFORM_TENANT_ID) : Optional.empty();
    }

    @Transactional
    public String createHighRiskApproval(String tenantId, String customerId, String businessTaskId,
                                         String orderId) {
        List<String> existing = db.sql("select id from support_case where tenant_id=:tenant and business_task_id=:task")
                .param("tenant", tenantId).param("task", businessTaskId).query(String.class).list();
        if (!existing.isEmpty()) return existing.getFirst();
        String storeId = db.sql("select p.store_id from customer_order o join product p on p.id=o.product_id " +
                        "and p.tenant_id=o.tenant_id where o.tenant_id=:tenant and o.user_id=:user and o.id=:orderId")
                .param("tenant", tenantId).param("user", customerId).param("orderId", orderId)
                .query(String.class).optional().orElse(DEFAULT_STORE);
        AssessmentDigest assessment = db.sql("select score,summary from refund_assessment " +
                        "where tenant_id=:tenant and business_task_id=:task")
                .param("tenant", tenantId).param("task", businessTaskId)
                .query((rs, row) -> new AssessmentDigest(rs.getInt("score"), rs.getString("summary")))
                .optional().orElse(new AssessmentDigest(0, "未取得规则评分摘要，请人工核对退款理由和证据"));
        return createCase(tenantId, customerId, storeId, null, businessTaskId, orderId,
                "HIGH_RISK_APPROVAL", "STORE", "退款任务 " + businessTaskId + "，订单 " + orderId +
                        "。理由充分度 " + assessment.score() + " 分：" + assessment.summary() +
                        "。请由店铺客服审核；同意后直接退款。", "HIGH");
    }

    public List<SupportCaseView> customerCases(SessionAccount customer) {
        return db.sql("select * from support_case where customer_id=:customer order by updated_at desc")
                .param("customer", customer.id()).query(this::mapCase).list();
    }

    public List<SupportCaseView> staffCases(SessionAccount staff) {
        if (staff.role() == AccountRole.STORE_AGENT) {
            return db.sql("select * from support_case where tenant_id=:tenant and queue_name='STORE' and store_id=:store " +
                            "and status<>'RESOLVED' order by updated_at desc")
                    .param("tenant", staff.tenantId()).param("store", staff.storeId()).query(this::mapCase).list();
        }
        return db.sql("select * from support_case where queue_name='OFFICIAL' and type<>'HIGH_RISK_APPROVAL' " +
                        "and status<>'RESOLVED' order by updated_at desc")
                .query(this::mapCase).list();
    }

    public SupportCaseView staffCase(String caseId, SessionAccount staff) {
        return requireStaffVisible(caseId, staff);
    }

    public List<SupportMessageView> messages(String caseId, SessionAccount account) {
        requireVisible(caseId, account);
        return db.sql("select * from support_message where case_id=:caseId order by created_at")
                .param("caseId", caseId).query(this::mapMessage).list();
    }

    @Transactional
    public SupportCaseView claim(String caseId, SessionAccount staff) {
        SupportCaseView item = requireStaffVisibleForUpdate(caseId, staff);
        if ("RESOLVED".equals(item.status())) throw new IllegalArgumentException("该会话已经结束");
        if (item.assigneeId() != null && !item.assigneeId().equals(staff.id())) {
            throw new IllegalArgumentException("该会话已被其他客服接管");
        }
        int changed = db.sql("update support_case set assignee_id=:staff,status='IN_PROGRESS',updated_at=current_timestamp " +
                        "where id=:id and (assignee_id is null or assignee_id=:staff)")
                .param("staff", staff.id()).param("id", caseId).update();
        if (changed != 1) throw new IllegalArgumentException("该会话已被其他客服接管，请刷新列表");
        addMessage(caseId, staff.id(), staff.role().name(), staff.displayName() + " 已接管会话");
        return requireStaffVisible(caseId, staff);
    }

    @Transactional
    public void customerReply(String caseId, String content, SessionAccount customer) {
        SupportCaseView item = requireVisible(caseId, customer);
        if (item.status().equals("RESOLVED")) throw new IllegalArgumentException("该会话已经结束");
        addMessage(caseId, customer.id(), customer.role().name(), requireContent(content));
        touch(caseId);
    }

    @Transactional
    public SupportCaseView bindComplaintOrder(String caseId, String orderId, SessionAccount customer) {
        SupportCaseView item = requireVisible(caseId, customer);
        if (!"COMPLAINT".equals(item.type())) throw new IllegalArgumentException("只有投诉会话需要选择订单");
        RecentOrder selected = recentOrders(customer.tenantId(), customer.id()).stream()
                .filter(order -> order.id().equals(orderId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在或不属于当前用户"));
        db.sql("update support_case set tenant_id=:tenant,order_id=:orderId,store_id=:store," +
                        "updated_at=current_timestamp where id=:id")
                .param("tenant", selected.tenantId()).param("orderId", selected.id())
                .param("store", selected.storeId()).param("id", caseId).update();
        addMessage(caseId, customer.id(), customer.role().name(),
                "我要沟通订单 " + selected.id() + "（" + selected.productName() + "）");
        addMessage(caseId, "OFFICIAL-ORDER-ASSISTANT", AccountRole.OFFICIAL_AGENT.name(),
                "已关联该订单。商城官方客服将基于订单事实继续处理投诉。");
        return getCase(caseId);
    }

    @Transactional
    public void staffReply(String caseId, String content, SessionAccount staff) {
        SupportCaseView item = requireStaffVisibleForUpdate(caseId, staff);
        if (!staff.id().equals(item.assigneeId())) throw new SecurityException("请先接管该会话再回复");
        if ("RESOLVED".equals(item.status())) throw new IllegalArgumentException("该会话已经结束");
        addMessage(caseId, staff.id(), staff.role().name(), requireContent(content));
        touch(caseId);
    }

    @Transactional
    public SupportCaseView resolve(String caseId, SessionAccount staff) {
        SupportCaseView item = requireStaffVisibleForUpdate(caseId, staff);
        if (!staff.id().equals(item.assigneeId())) throw new SecurityException("请先接管该会话再关闭");
        if ("RESOLVED".equals(item.status())) throw new IllegalArgumentException("该会话已经结束");
        if (item.type().equals("HIGH_RISK_APPROVAL") && !List.of("REFUNDED", "REJECTED")
                .contains(stateMachine.status(item.businessTaskId(), item.tenantId()))) {
            throw new IllegalArgumentException("高危操作必须完成审批决定后才能关闭");
        }
        db.sql("update support_case set status='RESOLVED',updated_at=current_timestamp where id=:id")
                .param("id", caseId).update();
        addMessage(caseId, staff.id(), staff.role().name(), "会话已结束");
        return getCase(caseId);
    }

    /** 低于自动退款阈值的申请只由订单所属店铺审核；店铺同意后立即执行退款。 */
    @Transactional
    public SupportCaseView decide(String caseId, StaffDecisionRequest request, SessionAccount staff) {
        if (staff.role() != AccountRole.STORE_AGENT)
            throw new SecurityException("退款审核仅由订单所属店铺客服处理");
        SupportCaseView item = requireStaffVisibleForUpdate(caseId, staff);
        if (!staff.id().equals(item.assigneeId())) throw new SecurityException("请先接管该会话再审批");
        if (!"HIGH_RISK_APPROVAL".equals(item.type()) || item.businessTaskId() == null) {
            throw new IllegalArgumentException("该会话不是高危操作审批");
        }
        String decision = request.decision() == null ? "" : request.decision().strip().toUpperCase(Locale.ROOT);
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
            throw new IllegalArgumentException("decision 只能为 APPROVE 或 REJECT");
        }
        String stage = "STORE";
        String expected = "WAITING_STORE_APPROVAL";
        if (!expected.equals(stateMachine.status(item.businessTaskId(), item.tenantId()))) throw new IllegalArgumentException("当前不在该客服的审批阶段");

        db.sql("insert into staff_decision(id,case_id,business_task_id,stage,decision,decided_by,comment,created_at) " +
                        "values(:id,:caseId,:task,:stage,:decision,:staff,:comment,current_timestamp)")
                .param("id", "DEC-" + shortId()).param("caseId", caseId).param("task", item.businessTaskId())
                .param("stage", stage).param("decision", decision).param("staff", staff.id())
                .param("comment", request.comment()).update();

        if (decision.equals("REJECT")) {
            stateMachine.transitionRequired(item.businessTaskId(), item.tenantId(), expected, "REJECTED");
            db.sql("update support_case set status='RESOLVED',updated_at=current_timestamp where id=:id")
                    .param("id", caseId).update();
            addMessage(caseId, staff.id(), staff.role().name(), "审批拒绝：" + safeComment(request.comment()));
        } else {
            stateMachine.transitionRequired(item.businessTaskId(), item.tenantId(), expected, "APPROVED");
            java.math.BigDecimal balanceAfter = commerce.refundApprovedTask(
                    item.tenantId(), item.customerId(), item.businessTaskId());
            db.sql("update support_case set status='RESOLVED',updated_at=current_timestamp where id=:id")
                    .param("id", caseId).update();
            addMessage(caseId, staff.id(), staff.role().name(),
                    "店铺客服审核通过，退款已直接回滚至账户余额。当前余额 ¥" + balanceAfter.toPlainString());
        }
        return getCase(caseId);
    }

    private String createCase(String tenantId, String customerId, String storeId, String conversationId,
                              String businessTaskId, String orderId, String type, String queue, String summary, String risk) {
        String id = "CASE-" + shortId();
        db.sql("insert into support_case(id,tenant_id,customer_id,store_id,conversation_id,business_task_id,order_id,type,queue_name,summary,risk_level,status,created_at,updated_at) " +
                        "values(:id,:tenant,:customer,:store,:conversation,:task,:orderId,:type,:queue,:summary,:risk,'OPEN',current_timestamp,current_timestamp)")
                .param("id", id).param("tenant", tenantId).param("customer", customerId).param("store", storeId)
                .param("conversation", conversationId).param("task", businessTaskId).param("orderId", orderId).param("type", type)
                .param("queue", queue).param("summary", summary).param("risk", risk).update();
        addMessage(id, customerId, AccountRole.CUSTOMER.name(), summary);
        return id;
    }

    private SupportCaseView requireStaffVisible(String caseId, SessionAccount staff) {
        if (staff.role() == AccountRole.CUSTOMER) throw new SecurityException("该接口仅供客服使用");
        return requireVisible(caseId, staff);
    }

    /** 写操作先锁工单行，使接管、回复、审批和关闭在多客服并发下具有确定顺序。 */
    private SupportCaseView requireStaffVisibleForUpdate(String caseId, SessionAccount staff) {
        if (staff.role() == AccountRole.CUSTOMER) throw new SecurityException("该接口仅供客服使用");
        String tenantPredicate = staff.role() == AccountRole.OFFICIAL_AGENT ? "" : "and tenant_id=:tenant ";
        var query = db.sql("select * from support_case where id=:id " + tenantPredicate + "for update")
                .param("id", caseId);
        if (staff.role() != AccountRole.OFFICIAL_AGENT) query = query.param("tenant", staff.tenantId());
        SupportCaseView item = query.query(this::mapCase).optional()
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        boolean allowed = switch (staff.role()) {
            case CUSTOMER -> false;
            case STORE_AGENT -> item.queueName().equals("STORE") && item.storeId().equals(staff.storeId());
            case STORE_ADMIN -> false;
            case OFFICIAL_AGENT -> !"HIGH_RISK_APPROVAL".equals(item.type()) &&
                    (item.queueName().equals("OFFICIAL") ||
                            (item.assigneeId() != null && item.assigneeId().equals(staff.id())));
        };
        if (!allowed) throw new SecurityException("无权访问该人工会话");
        return item;
    }

    private SupportCaseView requireVisible(String caseId, SessionAccount account) {
        SupportCaseView item = getCase(caseId);
        boolean allowed = switch (account.role()) {
            case CUSTOMER -> item.customerId().equals(account.id());
            case STORE_AGENT -> item.tenantId().equals(account.tenantId()) && item.queueName().equals("STORE") && item.storeId().equals(account.storeId());
            case STORE_ADMIN -> false;
            case OFFICIAL_AGENT -> !"HIGH_RISK_APPROVAL".equals(item.type()) &&
                    (item.queueName().equals("OFFICIAL") ||
                            (item.assigneeId() != null && item.assigneeId().equals(account.id())));
        };
        if (!allowed) throw new SecurityException("无权访问该人工会话");
        return item;
    }

    private SupportCaseView getCase(String caseId) {
        return db.sql("select * from support_case where id=:id")
                .param("id", caseId).query(this::mapCase).optional()
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
    }

    private List<RecentOrder> recentOrders(String tenantId, String customerId) {
        String tenantPredicate = TenantService.PLATFORM_TENANT_ID.equals(tenantId)
                ? "" : "and o.tenant_id=:tenant ";
        var query = db.sql("select o.id,o.tenant_id,p.name product_name,p.store_id from customer_order o join product p " +
                        "on p.id=o.product_id and p.tenant_id=o.tenant_id where o.user_id=:user " + tenantPredicate +
                        "order by o.created_at desc limit 5")
                .param("user", customerId);
        if (!TenantService.PLATFORM_TENANT_ID.equals(tenantId)) query = query.param("tenant", tenantId);
        return query.query((rs, n) -> new RecentOrder(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("product_name"), rs.getString("store_id"))).list();
    }
    private String officialOrderQuestion(String tenantId, String customerId) {
        List<RecentOrder> orders = recentOrders(tenantId, customerId);
        if (orders.isEmpty()) return "我是商城官方客服。已查询你的近期订单，目前没有可关联订单。请说明本次投诉涉及的店铺或具体情况。";
        String options = orders.stream().map(order -> order.id() + "「" + order.productName() + "」")
                .reduce((left, right) -> left + "、" + right).orElse("");
        return "我是商城官方客服。已先查询到你的近期订单：" + options + "。请问本次要沟通哪一个订单？";
    }
    private void addMessage(String caseId, String senderId, String role, String content) {
        db.sql("insert into support_message(id,case_id,sender_id,sender_role,content,created_at) " +
                        "values(:id,:caseId,:sender,:role,:content,current_timestamp)")
                .param("id", "MSG-" + shortId()).param("caseId", caseId).param("sender", senderId)
                .param("role", role).param("content", content).update();
    }
    private void touch(String caseId) {
        db.sql("update support_case set updated_at=current_timestamp where id=:id").param("id", caseId).update();
    }
    private String requireContent(String value) {
        String content = value == null ? "" : value.strip();
        if (content.isEmpty() || content.length() > 2000) throw new IllegalArgumentException("消息长度必须为 1-2000 个字符");
        return content;
    }
    private String safeComment(String value) { return value == null || value.isBlank() ? "未填写原因" : value.strip(); }
    private String shortId() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private SupportCaseView mapCase(ResultSet rs, int row) throws SQLException {
        return new SupportCaseView(rs.getString("id"), rs.getString("tenant_id"), rs.getString("type"), rs.getString("queue_name"),
                rs.getString("status"), rs.getString("risk_level"), rs.getString("summary"),
                rs.getString("customer_id"), rs.getString("store_id"), rs.getString("conversation_id"),
                rs.getString("business_task_id"), rs.getString("order_id"), rs.getString("assignee_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private SupportMessageView mapMessage(ResultSet rs, int row) throws SQLException {
        return new SupportMessageView(rs.getString("id"), rs.getString("case_id"), rs.getString("sender_id"),
                rs.getString("sender_role"), rs.getString("content"), rs.getTimestamp("created_at").toInstant());
    }
    private record RecentOrder(String id, String tenantId, String productName, String storeId) {}
    private record AssessmentDigest(int score, String summary) {}
    private record OfficialOrderContext(String id, String tenantId, String productName, String storeId, String storeName,
                                        String status, String paymentStatus, String logisticsStatus,
                                        String plannedShipAt, String estimatedArrivalAt) {}
    private record StoreProduct(String tenantId, String storeId, String productName, String storeName) {}
    public record StoreAiSession(String conversationId, String tenantId, String storeId, String storeName,
                                 String productId, String productName, String greeting) {}
    public record OfficialAiSession(String conversationId, String greeting) {}
}
