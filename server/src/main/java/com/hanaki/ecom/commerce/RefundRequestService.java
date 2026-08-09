package com.hanaki.ecom.commerce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.agent.AiModelGateway;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import com.hanaki.ecom.domain.Domain.RefundAssessmentView;
import com.hanaki.ecom.domain.Domain.RefundEvidenceDescriptor;
import com.hanaki.ecom.domain.Domain.RefundEvidenceView;
import com.hanaki.ecom.domain.Domain.RefundReasonAssessmentRequest;
import com.hanaki.ecom.domain.Domain.RefundReasonScore;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import com.hanaki.ecom.store.EcommerceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 退款理由、媒体证据和规则评分的服务端事实源。
 *
 * <p>媒体内容不直接进入文本模型：当前客服基模用于结合已发布知识规则评估用户明确陈述；图片和
 * 视频作为受鉴权保护的证据交由店铺客服查看。模型或知识不可用时一律保守进入店铺人工审核。</p>
 */
@Service
public class RefundRequestService {
    private static final Logger log = LoggerFactory.getLogger(RefundRequestService.class);
    private static final String PRE_SHIPMENT_CANCELLATION_RULE = "K-CANCEL-01";
    private static final int DETERMINISTIC_CANCELLATION_SCORE = 90;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcClient db;
    private final EcommerceStore store;
    private final AiModelGateway model;
    private final ObjectMapper json;
    private final Path evidenceRoot;
    private final int maxFiles;
    private final long maxImageBytes;
    private final long maxVideoBytes;
    private final int autoApproveThreshold;
    private final String modelVersion;

    public RefundRequestService(JdbcClient db, EcommerceStore store, AiModelGateway model, ObjectMapper json,
                                @Value("${agent.refund.evidence-dir:./data/refund-evidence}") String evidenceDir,
                                @Value("${agent.refund.max-files:4}") int maxFiles,
                                @Value("${agent.refund.max-image-bytes:10485760}") long maxImageBytes,
                                @Value("${agent.refund.max-video-bytes:104857600}") long maxVideoBytes,
                                @Value("${agent.refund.auto-approve-threshold:80}") int autoApproveThreshold,
                                @Value("${agent.refund.assessment-model:${spring.ai.dashscope.chat.options.model:qwen-plus}}") String modelVersion) {
        this.db = db;
        this.store = store;
        this.model = model;
        this.json = json;
        this.evidenceRoot = Path.of(evidenceDir).toAbsolutePath().normalize();
        this.maxFiles = Math.max(1, maxFiles);
        this.maxImageBytes = Math.max(1, maxImageBytes);
        this.maxVideoBytes = Math.max(1, maxVideoBytes);
        this.autoApproveThreshold = Math.max(0, Math.min(99, autoApproveThreshold));
        this.modelVersion = modelVersion == null || modelVersion.isBlank() ? "unknown" : modelVersion.strip();
    }

    public RefundAssessmentView assess(String taskId, String tenantId, String userId, OrderSummary order,
                                       String rawReason, List<MultipartFile> rawFiles) {
        validateRequestable(order);
        Optional<RefundAssessmentView> existing = find(taskId, tenantId, userId);
        if (existing.isPresent()) return existing.get();

        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() > 2_000) throw new IllegalArgumentException("退款理由不能超过 2000 个字符");
        List<MultipartFile> files = rawFiles == null ? List.of() : rawFiles.stream()
                .filter(file -> file != null && !file.isEmpty()).toList();
        if (files.size() > maxFiles) throw new IllegalArgumentException("退款证据最多上传 " + maxFiles + " 个文件");
        if (reason.isBlank() && files.isEmpty()) throw new IllegalArgumentException("请填写退款理由或上传图片、视频证据");

        List<StoredEvidence> stored = new ArrayList<>();
        boolean assessmentInserted = false;
        try {
            for (MultipartFile file : files) stored.add(storeFile(taskId, tenantId, userId, file));
            List<KnowledgeDoc> rules = store.knowledge(tenantId, "AFTER_SALE");
            RefundReasonScore result = score(tenantId, order, reason, rules, stored);
            int score = Math.max(0, Math.min(100, result.score()));
            // 纯媒体输入必须由人工查看，不能因为“上传了文件”就自动退款。
            if (reason.isBlank()) score = Math.min(score, autoApproveThreshold);
            boolean policyEligible = result.policyEligible() && !rules.isEmpty();
            RefundWindow window = refundWindow(order);
            List<String> matchedRuleIds = new ArrayList<>(safeList(result.matchedRuleIds()));
            List<String> missingInformation = new ArrayList<>(safeList(result.missingInformation()));
            String summary = safeSummary(result.summary());
            if (deterministicPreShipmentCancellation(order, reason, rules, window)) {
                score = Math.max(score, DETERMINISTIC_CANCELLATION_SCORE);
                policyEligible = true;
                if (!matchedRuleIds.contains(PRE_SHIPMENT_CANCELLATION_RULE))
                    matchedRuleIds.add(PRE_SHIPMENT_CANCELLATION_RULE);
                missingInformation.clear();
                summary = "订单为余额支付且尚未实际发货，取消申请符合未发货订单取消规则。";
            }
            // 已经发货、尚未签收时，“不想要了”只是主观变更意愿，既不满足未发货取消规则，
            // 也没有质量问题证据。模型即使误判命中规则，也不能把充分度抬过自动退款门槛。
            if (inTransitChangeOfMind(reason, files, window)) {
                score = Math.min(score, 60);
                policyEligible = false;
                matchedRuleIds.remove(PRE_SHIPMENT_CANCELLATION_RULE);
                if (!missingInformation.contains("商品已发货，需人工安排物流拦截或退货"))
                    missingInformation.add("商品已发货，需人工安排物流拦截或退货");
                summary = "订单已经发货且客户仅表示不想要，当前理由不满足未发货取消规则。";
            }
            // 未实际发货的订单允许按取消规则自动退款；已发货未签收需要人工处理拦截/退货，
            // 已签收订单则从权威签收时间开始计算七天窗口。
            boolean automaticWindow = window.automaticEligible();
            String decisionMode = policyEligible && automaticWindow && score > autoApproveThreshold
                    ? "AUTO_REFUND" : "STORE_REVIEW";
            if (!automaticWindow) {
                missingInformation.add(window.manualReviewReason());
                summary = safeSummary(summary + " " + window.manualReviewReason() + "，本次申请转店铺人工审核。");
            }
            String ruleVersion = ruleVersion(rules);
            insertAssessment(taskId, tenantId, userId, order.id(), reason, score, policyEligible, decisionMode,
                    summary, safeList(matchedRuleIds), safeList(missingInformation), ruleVersion);
            assessmentInserted = true;
            for (int index = 0; index < stored.size(); index++)
                insertEvidence(taskId, tenantId, userId, stored.get(index), index);
            return require(taskId, tenantId, userId);
        } catch (RuntimeException error) {
            if (assessmentInserted) {
                db.sql("delete from refund_evidence where business_task_id=:task and tenant_id=:tenant")
                        .param("task", taskId).param("tenant", tenantId).update();
                db.sql("delete from refund_assessment where business_task_id=:task and tenant_id=:tenant")
                        .param("task", taskId).param("tenant", tenantId).update();
            }
            cleanup(stored);
            throw error;
        }
    }

    public boolean automaticRefundAllowed(String taskId, String tenantId, String userId) {
        RefundAssessmentView value = require(taskId, tenantId, userId);
        return value.policyEligible() && value.score() > autoApproveThreshold
                && "AUTO_REFUND".equals(value.decisionMode());
    }

    /** 两个退款入口共用的确定性前置校验，避免订单页和聊天入口采用不同规则。 */
    public void validateRequestable(OrderSummary order) {
        if (order == null) throw new IllegalArgumentException("订单不存在或无权访问");
        if (!"BALANCE_PAID".equals(order.paymentStatus()))
            throw new IllegalArgumentException("该订单当前不可申请余额退款");
    }

    /**
     * 自动退款的履约边界必须由服务端事实决定，不能相信用户文字：未实际发货可取消，
     * 已发货未签收需要人工处理拦截/退货，签收后以 delivered_at 计算七天期限。
     * 缺少履约行时不能证明订单是否发货，因此强制降级到店铺人工审核。
     */
    private RefundWindow refundWindow(OrderSummary order) {
        Optional<DeliveryFact> fact = db.sql("select shipped_at,delivered_at from order_fulfillment " +
                        "where tenant_id=:tenant and order_id=:order")
                .param("tenant", order.tenantId()).param("order", order.id())
                .query((rs, row) -> {
                    java.sql.Timestamp shipped = rs.getTimestamp("shipped_at");
                    java.sql.Timestamp delivered = rs.getTimestamp("delivered_at");
                    return new DeliveryFact(shipped == null ? null : shipped.toInstant(),
                            delivered == null ? null : delivered.toInstant());
                }).optional();
        if (fact.isEmpty())
            return new RefundWindow(false, false, "缺少权威履约记录，需人工核验退款资格");
        Instant shippedAt = fact.get().shippedAt();
        Instant deliveredAt = fact.get().deliveredAt();
        if (deliveredAt == null && shippedAt == null) return new RefundWindow(true, true, "");
        if (deliveredAt == null)
            return new RefundWindow(false, false, "订单已经发货但尚未签收，需人工处理物流拦截或退货安排");
        boolean withinSevenDays = !Instant.now().isAfter(deliveredAt.plus(7, ChronoUnit.DAYS));
        return new RefundWindow(withinSevenDays, false, withinSevenDays ? "" :
                "已超过签收后 7 个自然日，需人工核验质量问题等规则例外");
    }

    /** 模型负责开放式理由评分；规则条件完整的未发货取消由服务端确定性判定，避免模型分数漂移。 */
    private boolean deterministicPreShipmentCancellation(OrderSummary order, String reason,
                                                           List<KnowledgeDoc> rules, RefundWindow window) {
        boolean rulePublished = rules.stream().anyMatch(rule -> PRE_SHIPMENT_CANCELLATION_RULE.equals(rule.id()));
        String normalized = reason == null ? "" : reason.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        boolean cancellationIntent = contains(normalized, "取消", "不想要", "不要了", "下错", "买错", "误购", "重复购买");
        return rulePublished && window.preShipment() && cancellationIntent
                && "PROCESSING".equals(order.status())
                && "BALANCE_PAID".equals(order.paymentStatus())
                && order.logisticsStatus() != null && order.logisticsStatus().contains("待发货");
    }

    private boolean inTransitChangeOfMind(String reason, List<MultipartFile> files, RefundWindow window) {
        String normalized = reason == null ? "" : reason.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        boolean changeOfMind = contains(normalized, "不想要", "不要了", "改变主意", "买错", "下错", "误购");
        return changeOfMind && files.isEmpty() && !window.preShipment() && !window.automaticEligible()
                && window.manualReviewReason().contains("已经发货但尚未签收");
    }

    private boolean contains(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    public RefundAssessmentView require(String taskId, String tenantId, String userId) {
        return find(taskId, tenantId, userId)
                .orElseThrow(() -> new IllegalArgumentException("退款理由评分记录不存在"));
    }

    public Optional<RefundAssessmentView> find(String taskId, String tenantId, String userId) {
        return db.sql("select * from refund_assessment where business_task_id=:task and tenant_id=:tenant " +
                        "and user_id=:user")
                .param("task", taskId).param("tenant", tenantId).param("user", userId)
                .query(this::mapAssessment).optional();
    }

    public EvidenceDownload evidence(String evidenceId, SessionAccount account) {
        EvidenceRow row = db.sql("select e.*,p.store_id from refund_evidence e " +
                        "join business_task b on b.id=e.business_task_id and b.tenant_id=e.tenant_id " +
                        "join customer_order o on o.id=b.order_id and o.tenant_id=b.tenant_id " +
                        "join product p on p.id=o.product_id and p.tenant_id=o.tenant_id where e.id=:id")
                .param("id", evidenceId).query(this::mapEvidenceRow).optional()
                .orElseThrow(() -> new IllegalArgumentException("退款证据不存在"));
        boolean allowed = switch (account.role()) {
            case CUSTOMER -> row.userId().equals(account.id());
            case STORE_AGENT -> row.tenantId().equals(account.tenantId()) && row.storeId().equals(account.storeId());
            case STORE_ADMIN, OFFICIAL_AGENT -> false;
        };
        if (!allowed) throw new SecurityException("无权查看该退款证据");
        Path path = resolveStoredPath(row.storagePath());
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("退款证据文件已丢失");
        return new EvidenceDownload(path, row.contentType(), row.originalFilename(), row.sizeBytes());
    }

    private RefundReasonScore score(String tenantId, OrderSummary order, String reason,
                                    List<KnowledgeDoc> rules, List<StoredEvidence> evidence) {
        if (rules.isEmpty()) {
            return new RefundReasonScore(0, false, "没有找到可用的售后知识规则，已转店铺人工审核。",
                    List.of(), List.of("缺少已发布售后规则"));
        }
        try {
            RefundReasonScore result = model.scoreRefundReason(new RefundReasonAssessmentRequest(
                    tenantId, order.id(), order.productName(), order.status(), order.paymentStatus(),
                    order.logisticsStatus(), reason, List.copyOf(rules),
                    evidence.stream().map(StoredEvidence::descriptor).toList()));
            if (result == null) throw new IllegalStateException("模型没有返回评分");
            return result;
        } catch (RuntimeException error) {
            log.warn("Refund reason scoring degraded for task order {}: {}", order.id(), error.getClass().getSimpleName());
            return new RefundReasonScore(0, false, "退款理由评分暂时不可用，已安全转入店铺人工审核。",
                    List.of(), List.of("模型评分不可用"));
        }
    }

    private StoredEvidence storeFile(String taskId, String tenantId, String userId, MultipartFile file) {
        String contentType = normalizeContentType(file.getContentType());
        String mediaType = IMAGE_TYPES.contains(contentType) ? "IMAGE" : VIDEO_TYPES.contains(contentType) ? "VIDEO" : "";
        if (mediaType.isBlank()) throw new IllegalArgumentException("仅支持 JPG、PNG、WebP 图片和 MP4、WebM、MOV 视频");
        long limit = "IMAGE".equals(mediaType) ? maxImageBytes : maxVideoBytes;
        if (file.getSize() <= 0 || file.getSize() > limit)
            throw new IllegalArgumentException(mediaType.equals("IMAGE") ? "单张图片不能超过 10MB" : "单个视频不能超过 100MB");
        validateMagic(file, contentType);

        String id = "RE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        String extension = extension(contentType);
        Path directory = evidenceRoot.resolve(safeSegment(tenantId)).resolve(safeSegment(taskId)).normalize();
        if (!directory.startsWith(evidenceRoot)) throw new SecurityException("退款证据存储路径非法");
        Path target = directory.resolve(id + extension).normalize();
        Path temporary = directory.resolve(id + ".uploading").normalize();
        try {
            Files.createDirectories(directory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String relative = evidenceRoot.relativize(target).toString().replace('\\', '/');
            return new StoredEvidence(id, mediaType, contentType, originalFilename(file.getOriginalFilename()),
                    relative, file.getSize(), HexFormat.of().formatHex(digest.digest()), target,
                    new RefundEvidenceDescriptor(mediaType, contentType,
                            originalFilename(file.getOriginalFilename()), file.getSize()));
        } catch (Exception error) {
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
            try { Files.deleteIfExists(target); } catch (Exception ignored) {}
            throw new IllegalStateException("退款证据保存失败", error);
        }
    }

    private void validateMagic(MultipartFile file, String contentType) {
        try (InputStream input = file.getInputStream()) {
            byte[] head = input.readNBytes(16);
            boolean valid = switch (contentType) {
                case "image/jpeg" -> head.length >= 3 && u(head[0]) == 0xff && u(head[1]) == 0xd8 && u(head[2]) == 0xff;
                case "image/png" -> head.length >= 8 && u(head[0]) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
                case "image/webp" -> head.length >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP");
                case "video/mp4", "video/quicktime" -> head.length >= 12 && ascii(head, 4, "ftyp");
                case "video/webm" -> head.length >= 4 && u(head[0]) == 0x1a && u(head[1]) == 0x45
                        && u(head[2]) == 0xdf && u(head[3]) == 0xa3;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("上传文件内容与声明的媒体格式不一致");
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("无法读取上传的退款证据", error); }
    }

    private void insertAssessment(String taskId, String tenantId, String userId, String orderId, String reason,
                                  int score, boolean eligible, String mode, String summary,
                                  List<String> matched, List<String> missing, String ruleVersion) {
        try {
            db.sql("insert into refund_assessment(business_task_id,tenant_id,user_id,order_id,reason_text,score," +
                            "policy_eligible,decision_mode,summary,matched_rule_ids,missing_information,rule_version," +
                            "model_version,created_at,updated_at) values(:task,:tenant,:user,:orderId,:reason,:score," +
                            ":eligible,:mode,:summary,:matched,:missing,:rule,:model,current_timestamp,current_timestamp)")
                    .param("task", taskId).param("tenant", tenantId).param("user", userId).param("orderId", orderId)
                    .param("reason", reason).param("score", score).param("eligible", eligible).param("mode", mode)
                    .param("summary", summary).param("matched", json.writeValueAsString(matched))
                    .param("missing", json.writeValueAsString(missing)).param("rule", ruleVersion)
                    .param("model", modelVersion).update();
        } catch (Exception error) { throw new IllegalStateException("退款评分结果保存失败", error); }
    }

    private void insertEvidence(String taskId, String tenantId, String userId, StoredEvidence item, int displayOrder) {
        db.sql("insert into refund_evidence(id,business_task_id,tenant_id,user_id,media_type,content_type," +
                        "original_filename,storage_path,size_bytes,sha256,display_order,created_at) " +
                        "values(:id,:task,:tenant,:user,:media,:content,:filename,:path,:size,:sha,:displayOrder,current_timestamp)")
                .param("id", item.id()).param("task", taskId).param("tenant", tenantId).param("user", userId)
                .param("media", item.mediaType()).param("content", item.contentType())
                .param("filename", item.originalFilename()).param("path", item.storagePath())
                .param("size", item.sizeBytes()).param("sha", item.sha256())
                .param("displayOrder", displayOrder).update();
    }

    private RefundAssessmentView mapAssessment(ResultSet rs, int row) throws SQLException {
        String taskId = rs.getString("business_task_id");
        String tenantId = rs.getString("tenant_id");
        List<RefundEvidenceView> evidence = db.sql("select * from refund_evidence where business_task_id=:task " +
                        "and tenant_id=:tenant order by display_order,created_at,id")
                .param("task", taskId).param("tenant", tenantId).query((item, index) -> new RefundEvidenceView(
                        item.getString("id"), item.getString("media_type"), item.getString("content_type"),
                        item.getString("original_filename"), item.getLong("size_bytes"),
                        "/api/v1/refund-evidence/" + item.getString("id"))).list();
        return new RefundAssessmentView(taskId, rs.getString("order_id"), rs.getString("reason_text"),
                rs.getInt("score"), rs.getBoolean("policy_eligible"), rs.getString("decision_mode"),
                rs.getString("summary"), parseList(rs.getString("matched_rule_ids")),
                parseList(rs.getString("missing_information")), rs.getString("rule_version"),
                rs.getString("model_version"), evidence, rs.getTimestamp("created_at").toInstant());
    }

    private EvidenceRow mapEvidenceRow(ResultSet rs, int row) throws SQLException {
        return new EvidenceRow(rs.getString("tenant_id"), rs.getString("user_id"), rs.getString("store_id"),
                rs.getString("content_type"), rs.getString("original_filename"), rs.getString("storage_path"),
                rs.getLong("size_bytes"));
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, STRING_LIST); }
        catch (Exception ignored) { return List.of(); }
    }

    private String ruleVersion(List<KnowledgeDoc> rules) {
        String canonical = rules.stream().map(rule -> rule.id() + "@" + rule.version()).sorted()
                .reduce((left, right) -> left + "|" + right).orElse("none");
        return "refund-rules-" + sha256(canonical).substring(0, 16);
    }

    private String safeSummary(String value) {
        String summary = value == null || value.isBlank() ? "评分模型未提供摘要，转店铺人工审核。" : value.strip();
        return summary.substring(0, Math.min(1_000, summary.length()));
    }

    private List<String> safeList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().substring(0, Math.min(200, value.strip().length())))
                .distinct().limit(20).toList();
    }

    private void cleanup(List<StoredEvidence> stored) {
        for (StoredEvidence item : stored) try { Files.deleteIfExists(item.absolutePath()); } catch (Exception ignored) {}
    }

    private Path resolveStoredPath(String relative) {
        Path path = evidenceRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!path.startsWith(evidenceRoot)) throw new SecurityException("退款证据路径越界");
        return path;
    }

    private String normalizeContentType(String value) {
        if (value == null) return "";
        return value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private String originalFilename(String value) {
        String name = value == null ? "evidence" : value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank()) name = "evidence";
        return name.substring(0, Math.min(255, name.length()));
    }

    private String safeSegment(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) throw new SecurityException("退款证据作用域非法");
        return safe;
    }

    private String extension(String contentType) {
        return Map.of("image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp",
                "video/mp4", ".mp4", "video/webm", ".webm", "video/quicktime", ".mov").get(contentType);
    }

    private boolean ascii(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) return false;
        for (int index = 0; index < value.length(); index++) if (bytes[offset + index] != value.charAt(index)) return false;
        return true;
    }

    private int u(byte value) { return value & 0xff; }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("无法生成规则版本摘要", error); }
    }

    public record EvidenceDownload(Path path, String contentType, String originalFilename, long sizeBytes) {}
    private record RefundWindow(boolean automaticEligible, boolean preShipment, String manualReviewReason) {}
    private record DeliveryFact(Instant shippedAt, Instant deliveredAt) {}
    private record StoredEvidence(String id, String mediaType, String contentType, String originalFilename,
                                  String storagePath, long sizeBytes, String sha256, Path absolutePath,
                                  RefundEvidenceDescriptor descriptor) {}
    private record EvidenceRow(String tenantId, String userId, String storeId, String contentType,
                               String originalFilename, String storagePath, long sizeBytes) {}
}
