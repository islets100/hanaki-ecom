package com.hanaki.ecom.store;

import com.hanaki.ecom.agent.TokenBudgetEstimator;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.domain.Domain.FulfillmentView;
import com.hanaki.ecom.domain.Domain.LogisticsEvent;
import com.hanaki.ecom.domain.Domain.MerchantStore;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.domain.Domain.Product;
import com.hanaki.ecom.domain.Domain.ProductDetail;
import com.hanaki.ecom.security.TenantService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Repository
public class EcommerceStore {
    private final JdbcClient db;
    private final TokenBudgetEstimator tokens;

    @Autowired
    public EcommerceStore(JdbcClient db, TokenBudgetEstimator tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    /** 兼容不启动 Spring 容器的仓储单元测试。 */
    public EcommerceStore(JdbcClient db) { this(db, new TokenBudgetEstimator()); }

    public List<Product> products(String tenantId) {
        if (TenantService.PLATFORM_TENANT_ID.equals(tenantId)) {
            return db.sql("select p.* from product p join saas_tenant t on t.tenant_id=p.tenant_id " +
                            "and t.tenant_type='MERCHANT' and t.status='ACTIVE' where p.active=true order by p.id")
                    .query(this::mapProduct).list();
        }
        return db.sql("select * from product where tenant_id=:tenant and active=true order by id")
                .param("tenant", tenantId).query(this::mapProduct).list();
    }

    public Optional<Product> product(String tenantId, String productId) {
        List<Product> matches = products(tenantId).stream()
                .filter(p -> p.id().equalsIgnoreCase(productId)).limit(2).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("商品编号在多个商家下重复，请从商品详情重新选择");
        return matches.stream().findFirst();
    }

    public Optional<MerchantStore> merchantStore(String tenantId, String storeId) {
        return db.sql("select * from merchant_store where tenant_id=:tenant and id=:store")
                .param("tenant", tenantId).param("store", storeId).query((rs, n) -> new MerchantStore(
                        rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
                        rs.getString("logo_text"), rs.getString("description"), rs.getBigDecimal("service_score"),
                        rs.getBigDecimal("fulfillment_score"), rs.getString("location"))).optional();
    }

    public ProductDetail productDetail(String tenantId, String productId) {
        Product selected = product(tenantId, productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        MerchantStore merchant = merchantStore(selected.tenantId(), selected.storeId())
                .orElseThrow(() -> new IllegalArgumentException("商品所属店铺不存在"));
        List<Product> others = products(selected.tenantId()).stream()
                .filter(item -> item.storeId().equals(selected.storeId()) && !item.id().equals(selected.id()))
                .limit(4).toList();
        return new ProductDetail(selected, merchant, others);
    }

    public List<OrderSummary> recentOrders(String tenantId, String userId) {
        return recentOrders(tenantId, userId, "", "");
    }

    /** 当前店铺范围由服务端会话解析，浏览器和模型都不能传入该条件。 */
    public List<OrderSummary> recentOrdersForStore(String tenantId, String userId, String storeId) {
        if (storeId == null || storeId.isBlank()) return List.of();
        return recentOrders(tenantId, userId, storeId, "");
    }

    /** 商品详情客服默认只查询当前商品订单，避免命中同店铺的其他商品。 */
    public List<OrderSummary> recentOrdersForProduct(String tenantId, String userId,
                                                     String storeId, String productId) {
        if (storeId == null || storeId.isBlank() || productId == null || productId.isBlank()) return List.of();
        return recentOrders(tenantId, userId, storeId, productId);
    }

    private List<OrderSummary> recentOrders(String tenantId, String userId, String storeId, String productId) {
        String ownerFilter = TenantService.PLATFORM_TENANT_ID.equals(tenantId)
                ? "where o.user_id=:user " : "where o.tenant_id=:tenant and o.user_id=:user ";
        String storeFilter = storeId == null || storeId.isBlank() ? "" : "and p.store_id=:store ";
        String productFilter = productId == null || productId.isBlank() ? "" : "and o.product_id=:product ";
        var query = db.sql("select o.*,p.name product_name,s.name store_name,f.planned_ship_at,f.estimated_arrival_at " +
                        "from customer_order o join product p on p.id=o.product_id and p.tenant_id=o.tenant_id " +
                        "left join merchant_store s on s.id=p.store_id and s.tenant_id=p.tenant_id " +
                        "left join order_fulfillment f on f.order_id=o.id and f.tenant_id=o.tenant_id " +
                        ownerFilter + storeFilter + productFilter +
                        "order by o.created_at desc limit 10").param("user", userId);
        if (!TenantService.PLATFORM_TENANT_ID.equals(tenantId)) query = query.param("tenant", tenantId);
        if (storeId != null && !storeId.isBlank()) query = query.param("store", storeId);
        if (productId != null && !productId.isBlank()) query = query.param("product", productId);
        return query.query(this::mapOrderSummary).list();
    }

    /**
     * tenantId + userId + orderId 三重约束，防止只凭一个订单号跨用户查询。
     * 精确订单查询不能复用“最近 10 单”列表，否则较早但仍在售后期内的订单会被错误判为不存在。
     */
    public Optional<OrderSummary> ownedOrder(String tenantId, String userId, String orderId) {
        String ownerFilter = TenantService.PLATFORM_TENANT_ID.equals(tenantId)
                ? "o.user_id=:user " : "o.tenant_id=:tenant and o.user_id=:user ";
        var query = db.sql("select o.*,p.name product_name,s.name store_name,f.planned_ship_at,f.estimated_arrival_at " +
                        "from customer_order o join product p on p.id=o.product_id and p.tenant_id=o.tenant_id " +
                        "left join merchant_store s on s.id=p.store_id and s.tenant_id=p.tenant_id " +
                        "left join order_fulfillment f on f.order_id=o.id and f.tenant_id=o.tenant_id " +
                        "where " + ownerFilter + "and o.id=:orderId")
                .param("user", userId).param("orderId", orderId);
        if (!TenantService.PLATFORM_TENANT_ID.equals(tenantId)) query = query.param("tenant", tenantId);
        return query.query(this::mapOrderSummary).optional();
    }

    private OrderSummary mapOrderSummary(ResultSet rs, int row) throws SQLException {
        String orderId = rs.getString("id");
        return new OrderSummary(orderId, "••••" + orderId.substring(Math.max(0, orderId.length() - 4)),
                rs.getString("tenant_id"), rs.getString("user_id"), rs.getString("product_id"),
                rs.getString("product_name"), rs.getString("sku"), rs.getBigDecimal("amount"),
                rs.getString("status"), rs.getString("payment_status"), rs.getString("logistics_status"),
                rs.getString("store_name"), instant(rs.getTimestamp("planned_ship_at")),
                instant(rs.getTimestamp("estimated_arrival_at")), rs.getTimestamp("created_at").toInstant());
    }

    public List<LogisticsEvent> logistics(String tenantId, String userId, String orderId) {
        OrderSummary owned = ownedOrder(tenantId, userId, orderId).orElse(null);
        if (owned == null) return List.of();
        return db.sql("select event_time,location,description from logistics_event where tenant_id=:tenant and order_id=:order order by event_time desc")
                .param("tenant", owned.tenantId()).param("order", orderId).query((rs, n) -> new LogisticsEvent(
                        rs.getTimestamp("event_time").toInstant().toString(), rs.getString("location"), rs.getString("description"))).list();
    }

    public FulfillmentView fulfillment(String tenantId, String userId, String orderId) {
        OrderSummary owned = ownedOrder(tenantId, userId, orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在或不属于当前用户"));
        return db.sql("select * from order_fulfillment where tenant_id=:tenant and order_id=:order")
                .param("tenant", owned.tenantId()).param("order", orderId).query((rs, n) -> new FulfillmentView(
                        rs.getString("order_id"), rs.getString("store_id"), rs.getString("status"),
                        rs.getTimestamp("planned_ship_at").toInstant(), rs.getTimestamp("estimated_arrival_at").toInstant(),
                        instant(rs.getTimestamp("shipped_at")), instant(rs.getTimestamp("delivered_at")),
                        logistics(tenantId, userId, orderId))).optional()
                .orElseThrow(() -> new IllegalArgumentException("该历史订单尚无模拟物流计划"));
    }

    public List<KnowledgeDoc> knowledge(String tenantId, String domain) {
        String scope = TenantService.PLATFORM_TENANT_ID.equals(tenantId)
                ? "tenant_id=:tenant" : "tenant_id in (:tenant,'platform')";
        return db.sql("select * from knowledge_doc where " + scope + " and (domain=:domain or domain='COMMON') " +
                        "and active=true and (effective_at is null or effective_at<=current_timestamp) " +
                        "and (expires_at is null or expires_at>current_timestamp)")
                .param("tenant", tenantId).param("domain", domain).query((rs, n) -> new KnowledgeDoc(
                        rs.getString("id"), rs.getString("tenant_id"), rs.getString("domain"), rs.getString("title"),
                        rs.getString("content"), rs.getString("version"), 0)).list();
    }

    private Product mapProduct(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Product(rs.getString("id"), rs.getString("tenant_id"), rs.getString("store_id"),
                rs.getString("name"), rs.getString("subtitle"), rs.getString("category"),
                rs.getBigDecimal("price"), rs.getBigDecimal("old_price"), rs.getInt("stock"),
                rs.getString("badge"), rs.getString("attributes_json"));
    }

    /**
     * 供知识发布 Outbox 构建租户级搜索索引使用。与在线 knowledge(...) 读取采用完全相同的
     * active/effective/expires 约束，但不限制业务 domain，确保一次激活事件能把该租户当前所有
     * 可检索领域同步到新的写别名。调用方仍不能传入其他租户或任意 SQL 条件。
     */
    public List<KnowledgeDoc> activeKnowledge(String tenantId) {
        return db.sql("select * from knowledge_doc where tenant_id=:tenant and active=true " +
                        "and (effective_at is null or effective_at<=current_timestamp) " +
                        "and (expires_at is null or expires_at>current_timestamp)")
                .param("tenant", tenantId).query((rs, n) -> new KnowledgeDoc(
                        rs.getString("id"), rs.getString("tenant_id"), rs.getString("domain"), rs.getString("title"),
                        rs.getString("content"), rs.getString("version"), 0)).list();
    }

    public List<String> recentMessages(String tenantId, String userId, String conversationId, int limit) {
        return db.sql("select role || ': ' || content text from conversation_message " +
                        "where tenant_id=:tenant and user_id=:user and conversation_id=:conversation and deleted_at is null " +
                        "order by coalesce(message_seq,0) desc,created_at desc limit :limit")
                .param("tenant", tenantId).param("user", userId).param("conversation", conversationId).param("limit", limit)
                .query(String.class).list().reversed();
    }

    /**
     * 同步保存完整审计消息，并为每个会话分配严格递增的 message_seq。
     *
     * <p>摘要的 coveredEndSeq 和 CAS 都依赖该序号，所以不能用时间戳代替。事务先锁当前用户行，
     * 同一用户并发的两个 Run 会依次计算 max(message_seq)+1；随后唯一索引再提供数据库级兜底。
     * idempotency_key 仍保证 Graph 恢复重放不会重复插入同一角色消息。</p>
     */
    @Transactional
    public void saveMessage(String tenantId, String userId, String conversationId, String runId, String role, String content) {
        String idempotencyKey = sha256(runId + "|message|" + role);
        db.sql("select id from app_account where id=:user and " +
                        "(tenant_id=:tenant or (tenant_id='platform' and role='CUSTOMER')) for update")
                .param("tenant", tenantId).param("user", userId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("消息所属用户不存在或不属于当前租户"));
        int exists = db.sql("select count(*) from conversation_message where tenant_id=:tenant and user_id=:user " +
                        "and idempotency_key=:key")
                .param("tenant", tenantId).param("user", userId).param("key", idempotencyKey)
                .query(Integer.class).single();
        if (exists > 0) return;
        long nextSeq = db.sql("select coalesce(max(message_seq),0)+1 from conversation_message " +
                        "where tenant_id=:tenant and user_id=:user and conversation_id=:conversation")
                .param("tenant", tenantId).param("user", userId).param("conversation", conversationId)
                .query(Long.class).single();
        String safeContent = content == null ? "" : content;
        String trust = "USER".equalsIgnoreCase(role) ? "USER_CLAIMED" : "MODEL_EXTRACTED";
        db.sql("insert into conversation_message(id,tenant_id,user_id,conversation_id,run_id,role,content," +
                        "idempotency_key,message_seq,content_hash,token_count,trust_level,source_type,created_at) " +
                        "values(:id,:tenant,:user,:conversation,:run,:role,:content,:key,:seq,:hash,:tokens,:trust," +
                        "'CHAT',current_timestamp)")
                .param("id", UUID.randomUUID().toString()).param("tenant", tenantId).param("user", userId)
                .param("conversation", conversationId).param("run", runId).param("role", role).param("content", safeContent)
                .param("key", idempotencyKey).param("seq", nextSeq).param("hash", sha256(safeContent))
                .param("tokens", tokens.estimate(safeContent)).param("trust", trust).update();
    }

    public void saveCheckpoint(String tenantId, String conversationId, String runId, String node, String stateJson) {
        saveCheckpoint(tenantId, null, conversationId, runId, node, stateJson,
                1L, "graph-v1", "legacy", null, null, Instant.now().plus(24, java.time.temporal.ChronoUnit.HOURS));
    }

    /**
     * 保存框架外的轻量 Checkpoint 审计投影。expiresAt 是“允许恢复执行”的期限，不等于法定审计
     * 保留期限；过期行可继续归档，但恢复器必须拒绝继续执行并按 businessTaskId 重建或转人工。
     */
    public void saveCheckpoint(String tenantId, String userId, String conversationId, String runId,
                               String node, String stateJson, long stateVersion, String graphVersion,
                               String promptVersion, String pendingActionType, String businessTaskId,
                               Instant expiresAt) {
        String idempotencyKey = sha256(runId + "|checkpoint|" + node);
        int updated = db.sql("update agent_checkpoint set user_id=:user,state_json=:state,schema_version='2.0'," +
                        "state_version=:stateVersion,graph_version=:graphVersion,prompt_version=:promptVersion," +
                        "pending_action_type=:pending,business_task_id=:task,expires_at=:expiry,status='ACTIVE'," +
                        "created_at=current_timestamp " +
                        "where tenant_id=:tenant and idempotency_key=:key")
                .param("user", userId).param("state", stateJson).param("stateVersion", stateVersion)
                .param("graphVersion", graphVersion).param("promptVersion", promptVersion)
                .param("pending", pendingActionType).param("task", businessTaskId)
                .param("expiry", Timestamp.from(expiresAt)).param("tenant", tenantId)
                .param("key", idempotencyKey).update();
        if (updated > 0) return;
        try {
            db.sql("insert into agent_checkpoint(id,tenant_id,user_id,conversation_id,run_id,node_name,state_json," +
                            "schema_version,idempotency_key,state_version,graph_version,prompt_version,pending_action_type," +
                            "business_task_id,expires_at,status,created_at) values(:id,:tenant,:user,:conversation,:run," +
                            ":node,:state,'2.0',:key,:stateVersion,:graphVersion,:promptVersion,:pending,:task,:expiry," +
                            "'ACTIVE',current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("tenant", tenantId).param("conversation", conversationId)
                    .param("user", userId).param("run", runId).param("node", node).param("state", stateJson)
                    .param("key", idempotencyKey).param("stateVersion", stateVersion)
                    .param("graphVersion", graphVersion).param("promptVersion", promptVersion)
                    .param("pending", pendingActionType).param("task", businessTaskId)
                    .param("expiry", Timestamp.from(expiresAt)).update();
        } catch (DataIntegrityViolationException concurrentReplay) {
            db.sql("update agent_checkpoint set user_id=:user,state_json=:state,schema_version='2.0'," +
                            "state_version=:stateVersion,graph_version=:graphVersion,prompt_version=:promptVersion," +
                            "pending_action_type=:pending,business_task_id=:task,expires_at=:expiry,status='ACTIVE'," +
                            "created_at=current_timestamp " +
                            "where tenant_id=:tenant and idempotency_key=:key")
                    .param("user", userId).param("state", stateJson).param("stateVersion", stateVersion)
                    .param("graphVersion", graphVersion).param("promptVersion", promptVersion)
                    .param("pending", pendingActionType).param("task", businessTaskId)
                    .param("expiry", Timestamp.from(expiresAt)).param("tenant", tenantId)
                    .param("key", idempotencyKey).update();
        }
    }

    /**
     * 保存带完整配置版本的轻量 Checkpoint。旧签名继续用于兼容测试和历史调用；正式 Agent Run 使用
     * 此重载，把等待确认前采用的所有版本固定下来。恢复器若发现目标版本已下线，必须执行显式迁移
     * 和规则重校验，不能静默加载“最新配置”。
     */
    public void saveCheckpoint(String tenantId, String userId, String conversationId, String runId,
                               String node, String stateJson, long stateVersion, String graphVersion,
                               String promptVersion, String tenantConfigVersion, String policyVersion,
                               String knowledgeBaseVersion, String toolSchemaVersion,
                               String routingConfigVersion, String topologyVersion,
                               String pendingActionType, String businessTaskId, Instant expiresAt) {
        saveCheckpoint(tenantId, userId, conversationId, runId, node, stateJson, stateVersion,
                graphVersion, promptVersion, pendingActionType, businessTaskId, expiresAt);
        String idempotencyKey = sha256(runId + "|checkpoint|" + node);
        db.sql("update agent_checkpoint set tenant_config_version=:tenantConfig,policy_version=:policy," +
                        "knowledge_base_version=:knowledge,tool_schema_version=:toolSchema," +
                        "routing_config_version=:routing,topology_version=:topology " +
                        "where tenant_id=:tenant and idempotency_key=:key")
                .param("tenantConfig", tenantConfigVersion).param("policy", policyVersion)
                .param("knowledge", knowledgeBaseVersion).param("toolSchema", toolSchemaVersion)
                .param("routing", routingConfigVersion).param("topology", topologyVersion)
                .param("tenant", tenantId).param("key", idempotencyKey).update();
    }

    @Transactional
    public String createBusinessTask(String tenantId, String userId, String orderId, String type, String status, String ruleVersion) {
        return createBusinessTask(tenantId, userId, orderId, type, status, ruleVersion, UUID.randomUUID().toString());
    }

    @Transactional
    public String createBusinessTask(String tenantId, String userId, String orderId, String type, String status,
                                     String ruleVersion, String idempotencySource) {
        String idempotencyKey = sha256(tenantId + "|" + userId + "|" + idempotencySource);
        // 所有同一用户的业务任务创建先锁账户行，避免约束异常破坏 PostgreSQL 当前事务。
        db.sql("select id from app_account where id=:user and " +
                        "(tenant_id=:tenant or (tenant_id='platform' and role='CUSTOMER')) for update")
                .param("tenant", tenantId).param("user", userId).query(String.class).single();
        String existing = db.sql("select id from business_task where tenant_id=:tenant and user_id=:user " +
                        "and idempotency_key=:key")
                .param("tenant", tenantId).param("user", userId).param("key", idempotencyKey)
                .query(String.class).optional().orElse(null);
        if (existing != null) return existing;
        String active = db.sql("select id from business_task where tenant_id=:tenant and user_id=:user " +
                        "and order_id=:orderId and type=:type and status in ('WAITING_CONFIRMATION'," +
                        "'WAITING_STORE_APPROVAL','WAITING_OFFICIAL_APPROVAL','APPROVED','EXECUTING') " +
                        "order by created_at desc limit 1")
                .param("tenant", tenantId).param("user", userId).param("orderId", orderId).param("type", type)
                .query(String.class).optional().orElse(null);
        if (active != null) return active;
        String id = "BT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        db.sql("insert into business_task(id,tenant_id,user_id,order_id,type,status,rule_version,version,expires_at,idempotency_key,created_at,updated_at) " +
                        "values(:id,:tenant,:user,:orderId,:type,:status,:rule,0,current_timestamp + interval '24' hour,:key,current_timestamp,current_timestamp)")
                .param("id", id).param("tenant", tenantId).param("user", userId).param("orderId", orderId)
                .param("type", type).param("status", status).param("rule", ruleVersion)
                .param("key", idempotencyKey).update();
        return id;
    }

    @Transactional
    public boolean transitionTask(String taskId, String tenantId, String expected, String target) {
        int changed = db.sql("update business_task set status=:target,version=version+1," +
                        "expires_at=case when :target in ('WAITING_CONFIRMATION','WAITING_STORE_APPROVAL','WAITING_OFFICIAL_APPROVAL') " +
                        "then current_timestamp + interval '24' hour else null end,updated_at=current_timestamp " +
                        "where id=:id and tenant_id=:tenant and status=:expected")
                .param("target", target).param("id", taskId).param("tenant", tenantId).param("expected", expected).update();
        if (changed == 1) {
            db.sql("insert into business_task_transition(id,business_task_id,tenant_id,source_state,target_state,transition_source,created_at) " +
                            "values(:id,:task,:tenant,:source,:target,'SPRING_STATE_MACHINE',current_timestamp)")
                    .param("id", UUID.randomUUID().toString()).param("task", taskId).param("tenant", tenantId)
                    .param("source", expected).param("target", target).update();
        }
        return changed == 1;
    }

    public String taskStatus(String taskId, String tenantId) {
        return db.sql("select status from business_task where id=:id and tenant_id=:tenant")
                .param("id", taskId).param("tenant", tenantId).query(String.class).optional().orElse("");
    }

    public List<OverdueBusinessTask> overdueBusinessTasks() {
        return db.sql("select id,tenant_id,status from business_task where expires_at is not null " +
                        "and expires_at<=current_timestamp and status in ('WAITING_CONFIRMATION','WAITING_STORE_APPROVAL','WAITING_OFFICIAL_APPROVAL') " +
                        "order by expires_at limit 100")
                .query((rs, row) -> new OverdueBusinessTask(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("status"))).list();
    }

    public record OverdueBusinessTask(String taskId, String tenantId, String status) {}

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("无法生成幂等键", error); }
    }

    public String createTicket(String tenantId, String userId, String orderId, String summary, String riskLevel) {
        String id = "TK-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        db.sql("insert into ticket(id,tenant_id,user_id,order_id,type,summary,risk_level,priority,status,assignee,created_at) values(:id,:tenant,:user,:orderId,'COMPLAINT',:summary,:risk,'HIGH','OPEN','平台客服一组',current_timestamp)")
                .param("id", id).param("tenant", tenantId).param("user", userId).param("orderId", orderId)
                .param("summary", summary).param("risk", riskLevel).update();
        return id;
    }

    public void saveTrace(String traceId, String tenantId, String runId, String node, long elapsedMs, String result) {
        db.sql("insert into agent_trace(id,trace_id,tenant_id,run_id,node_name,elapsed_ms,result,created_at) values(:id,:trace,:tenant,:run,:node,:elapsed,:result,current_timestamp)")
                .param("id", UUID.randomUUID().toString()).param("trace", traceId).param("tenant", tenantId)
                .param("run", runId).param("node", node).param("elapsed", elapsedMs).param("result", result).update();
    }

    public Map<String, Object> metrics(String tenantId) {
        boolean platform = TenantService.PLATFORM_TENANT_ID.equals(tenantId);
        String conversationScope = platform ? "" : " where tenant_id=:tenant";
        String ticketScope = platform ? " where status<>'RESOLVED'" : " where tenant_id=:tenant and status<>'RESOLVED'";
        String taskScope = platform ? "" : " where tenant_id=:tenant";
        var conversationQuery = db.sql("select count(distinct conversation_id) from conversation_message" + conversationScope);
        var ticketQuery = db.sql("select count(*) from support_case" + ticketScope);
        var taskQuery = db.sql("select count(*) from business_task" + taskScope);
        if (!platform) {
            conversationQuery = conversationQuery.param("tenant", tenantId);
            ticketQuery = ticketQuery.param("tenant", tenantId);
            taskQuery = taskQuery.param("tenant", tenantId);
        }
        int conversations = conversationQuery.query(Integer.class).single();
        int tickets = ticketQuery.query(Integer.class).single();
        int tasks = taskQuery.query(Integer.class).single();
        return Map.of("todayConversations", conversations, "openTickets", tickets, "businessTasks", tasks,
                "autoResolutionRate", 0.867, "handoffRate", 0.082, "averageFirstResponseMs", 1420);
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
