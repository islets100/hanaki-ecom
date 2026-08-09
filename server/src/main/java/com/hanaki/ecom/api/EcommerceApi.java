package com.hanaki.ecom.api;

import com.hanaki.ecom.agent.AgentOrchestrator;
import com.hanaki.ecom.agent.ModelCallException;
import com.hanaki.ecom.agent.ToolGateway;
import com.hanaki.ecom.agent.KnowledgeGovernanceService;
import com.hanaki.ecom.agent.MemoryContextService;
import com.hanaki.ecom.agent.RequestExecutionStore.RequestInProgressException;
import com.hanaki.ecom.commerce.CommerceService;
import com.hanaki.ecom.commerce.RefundRequestService;
import com.hanaki.ecom.domain.Domain.*;
import com.hanaki.ecom.merchant.MerchantManagementService;
import com.hanaki.ecom.security.IdentityService;
import com.hanaki.ecom.security.IdentityService.AuthenticationException;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import com.hanaki.ecom.security.TenantService;
import com.hanaki.ecom.store.EcommerceStore;
import com.hanaki.ecom.support.SupportService;
import com.hanaki.ecom.observability.ObservabilityStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 商城与客服工作台共用同一后端，但使用完全不同的认证入口和服务端角色授权。
 * tenantId/userId 永远来自登录会话，不再相信浏览器自行传入的身份字段。
 */
@RestController
@RequestMapping("/api/v1")
public class EcommerceApi {
    private static final Set<AccountRole> CUSTOMERS = Set.of(AccountRole.CUSTOMER);
    private static final Set<AccountRole> STAFF = Set.of(AccountRole.STORE_AGENT, AccountRole.OFFICIAL_AGENT);
    private static final Set<AccountRole> MERCHANT_ADMINS = Set.of(AccountRole.STORE_ADMIN);
    private final EcommerceStore store;
    private final AgentOrchestrator agents;
    private final ToolGateway tools;
    private final IdentityService identities;
    private final TenantService tenants;
    private final SupportService support;
    private final CommerceService commerce;
    private final ObservabilityStore observability;
    private final KnowledgeGovernanceService knowledge;
    private final MemoryContextService memory;
    private final RefundRequestService refunds;
    private final MerchantManagementService merchants;

    public EcommerceApi(EcommerceStore store, AgentOrchestrator agents, ToolGateway tools,
                        IdentityService identities, TenantService tenants,
                        SupportService support, CommerceService commerce,
                        ObservabilityStore observability, KnowledgeGovernanceService knowledge,
                         MemoryContextService memory, RefundRequestService refunds,
                         MerchantManagementService merchants) {
        this.store = store;
        this.agents = agents;
        this.tools = tools;
        this.identities = identities;
        this.tenants = tenants;
        this.support = support;
        this.commerce = commerce;
        this.observability = observability;
        this.knowledge = knowledge;
        this.memory = memory;
        this.refunds = refunds;
        this.merchants = merchants;
    }

    // ---------- 两套网站的账号入口 ----------
    @PostMapping("/auth/customers/register")
    AuthResponse registerCustomer(@Valid @RequestBody CustomerRegisterRequest request) {
        return identities.registerCustomer(request);
    }

    @PostMapping("/auth/customers/login")
    AuthResponse loginCustomer(@Valid @RequestBody LoginRequest request) { return identities.loginCustomer(request); }

    @PostMapping("/auth/staff/register")
    AuthResponse registerStaff(@Valid @RequestBody StaffRegisterRequest request) {
        return identities.registerStaff(request);
    }

    @PostMapping("/auth/staff/login")
    AuthResponse loginStaff(@Valid @RequestBody LoginRequest request) { return identities.loginStaff(request); }

    /** 商家工作台登录页只获取可公开的商家租户代码和名称。 */
    @GetMapping("/auth/tenants")
    Object tenants() { return tenants.activeTenants(); }

    /** SaaS 商家开通入口：原子创建商家租户、默认店铺以及首个店铺管理员。 */
    @PostMapping("/auth/tenants")
    AuthResponse provisionTenant(@Valid @RequestBody TenantProvisionRequest request) {
        return identities.provisionTenant(request);
    }

    @GetMapping("/auth/me")
    AccountView me(@RequestHeader("Authorization") String authorization) {
        SessionAccount account = identities.require(authorization,
                Set.of(AccountRole.CUSTOMER, AccountRole.STORE_ADMIN,
                        AccountRole.STORE_AGENT, AccountRole.OFFICIAL_AGENT));
        return accountView(account);
    }

    @PostMapping("/auth/logout")
    Map<String, Object> logout(@RequestHeader("Authorization") String authorization) {
        identities.logout(authorization);
        return Map.of("success", true);
    }

    // ---------- 用户商城 ----------
    @GetMapping("/products")
    Object products() {
        return store.products(TenantService.PLATFORM_TENANT_ID);
    }

    @GetMapping("/products/{id}")
    Object productDetail(@PathVariable String id) {
        return store.productDetail(TenantService.PLATFORM_TENANT_ID, id);
    }

    @GetMapping("/balance")
    BalanceView balance(@RequestHeader("Authorization") String authorization) {
        return commerce.balance(identities.require(authorization, CUSTOMERS));
    }

    @GetMapping("/balance/ledger")
    Object balanceLedger(@RequestHeader("Authorization") String authorization) {
        return commerce.ledger(identities.require(authorization, CUSTOMERS));
    }

    @PostMapping("/orders/purchase")
    PurchaseResponse purchase(@Valid @RequestBody PurchaseRequest request,
                              @RequestHeader("Authorization") String authorization) {
        return commerce.purchase(identities.require(authorization, CUSTOMERS), request);
    }

    /** 用户可提交文字及媒体证据；高分申请确认后自动退款，其余只进入所属店铺审核。 */
    @PostMapping(value = "/orders/{id}/refund-request", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    RefundRequestResponse requestRefund(@PathVariable String id,
                                        @RequestPart(value = "reason", required = false) String reason,
                                        @RequestPart(value = "evidence", required = false) List<MultipartFile> evidence,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        OrderSummary order = store.ownedOrder(customer.tenantId(), customer.id(), id)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在或无权访问"));
        refunds.validateRequestable(order);
        String businessTenantId = order.tenantId();
        String taskId = store.createBusinessTask(businessTenantId, customer.id(), id,
                "REFUND", "WAITING_CONFIRMATION", "after-sale-v12",
                "manual-refund|" + id + "|" + blankOr(idempotencyKey, UUID.randomUUID().toString()));
        String currentStatus = store.taskStatus(taskId, businessTenantId);
        RefundAssessmentView assessment = refunds.find(taskId, businessTenantId, customer.id()).orElse(null);
        // 同一个幂等键重试时，任务可能已经确认并进入审核或退款。此时不能再签发一个会失败的确认令牌。
        if (!"WAITING_CONFIRMATION".equals(currentStatus)) {
            return new RefundRequestResponse(taskId, "", currentStatus,
                    "该退款申请已存在，当前状态：" + currentStatus + "。系统没有重复创建任务或退款。", assessment);
        }
        assessment = refunds.assess(taskId, businessTenantId, customer.id(), order, reason, evidence);
        String confirmToken = tools.issueConfirmToken(businessTenantId, customer.id(), taskId, id,
                "submit_refund", order.amount(), assessment.ruleVersion());
        String next = "AUTO_REFUND".equals(assessment.decisionMode())
                ? "理由充分度高于 80 分；确认后系统将自动退款，无需人工审核。"
                : "确认后将交由该订单所属店铺客服审核；店铺同意后直接退款。";
        String message = "退款理由评分：" + assessment.score() + " 分。" + assessment.summary() + " " + next;
        return new RefundRequestResponse(taskId, confirmToken, currentStatus, message, assessment);
    }

    @GetMapping("/refund-evidence/{id}")
    ResponseEntity<Resource> refundEvidence(@PathVariable String id,
                                            @RequestHeader("Authorization") String authorization) {
        SessionAccount account = identities.require(authorization,
                Set.of(AccountRole.CUSTOMER, AccountRole.STORE_AGENT));
        RefundRequestService.EvidenceDownload item = refunds.evidence(id, account);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(item.contentType()))
                .contentLength(item.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(item.originalFilename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(item.path()));
    }

    @GetMapping("/orders")
    Object orders(@RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return store.recentOrders(customer.tenantId(), customer.id());
    }

    @GetMapping("/orders/{id}/logistics")
    Object logistics(@PathVariable String id, @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return store.logistics(customer.tenantId(), customer.id(), id);
    }

    @GetMapping("/orders/{id}/fulfillment")
    FulfillmentView fulfillment(@PathVariable String id, @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return store.fulfillment(customer.tenantId(), customer.id(), id);
    }

    @PostMapping("/chat")
    ChatResponse chat(@Valid @RequestBody ChatRequest request, @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        if (request.content() == null || request.content().isBlank()) throw new IllegalArgumentException("content 不能为空");
        String conversationId = blankOr(request.conversationId(), "web-" + customer.id());
        String messageId = blankOr(request.messageId(), UUID.randomUUID().toString());
        String contextTenantId = support.conversationTenant(customer.id(), conversationId)
                .orElse(TenantService.PLATFORM_TENANT_ID);
        return agents.chat(new ChatRequest(contextTenantId, customer.id(), conversationId, messageId,
                request.content().strip()));
    }

    @PostMapping("/tasks/confirm")
    ConfirmResponse confirm(@Valid @RequestBody ConfirmRequest request,
                            @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return tools.confirm(request.confirmToken(), customer.tenantId(), customer.id(), request.confirmed());
    }

    @GetMapping("/customer/support/cases")
    Object customerCases(@RequestHeader("Authorization") String authorization) {
        return support.customerCases(identities.require(authorization, CUSTOMERS));
    }

    @GetMapping("/customer/support/cases/{id}/messages")
    Object customerMessages(@PathVariable String id, @RequestHeader("Authorization") String authorization) {
        return support.messages(id, identities.require(authorization, CUSTOMERS));
    }

    @PostMapping("/customer/support/cases/{id}/messages")
    Map<String, Object> customerReply(@PathVariable String id, @Valid @RequestBody SupportMessageRequest request,
                                      @RequestHeader("Authorization") String authorization) {
        support.customerReply(id, request.content(), identities.require(authorization, CUSTOMERS));
        return Map.of("success", true);
    }

    @PostMapping("/customer/support/store-contact")
    Map<String, String> contactStore(@Valid @RequestBody StoreContactRequest request,
                                     @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        String caseId = support.createStoreContact(customer.tenantId(), customer.id(),
                request.productId(), request.message());
        return Map.of("caseId", caseId);
    }

    /**
     * 商品详情页默认进入店铺智能客服，不立即占用人工队列。
     * 会话绑定的店铺信息由服务端根据 productId 查询，不能由浏览器伪造。
     */
    @PostMapping("/customer/support/store-ai")
    SupportService.StoreAiSession startStoreAi(@Valid @RequestBody StoreContactRequest request,
                                                @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return support.startStoreAi(customer.tenantId(), customer.id(), request.productId());
    }

    /** 页面右上角官方客服入口：默认启动官方 AI，不在此处创建人工投诉工单。 */
    @PostMapping("/customer/support/official-ai")
    SupportService.OfficialAiSession startOfficialAi(@RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return support.startOfficialAi(customer.tenantId(), customer.id());
    }

    /**
     * 兼容旧客户端的人工入口；新版用户网站不再直接调用该接口。
     * 创建会话后，服务端会先列出该用户的近期订单，并等待用户选择本次投诉关联的订单。
     */
    @PostMapping("/customer/support/official-contact")
    Map<String, String> contactOfficial(@Valid @RequestBody SupportMessageRequest request,
                                        @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        String summary = request.content() == null || request.content().isBlank()
                ? "用户申请联系商城官方客服" : request.content().strip();
        String caseId = support.createHandoff(customer.tenantId(), customer.id(),
                "official-" + UUID.randomUUID(), summary, true);
        return Map.of("caseId", caseId);
    }

    @PostMapping("/customer/support/cases/{id}/order")
    SupportCaseView bindComplaintOrder(@PathVariable String id, @Valid @RequestBody OrderSelectionRequest request,
                                       @RequestHeader("Authorization") String authorization) {
        return support.bindComplaintOrder(id, request.orderId(), identities.require(authorization, CUSTOMERS));
    }

    @GetMapping("/customer/memory/candidates")
    Object memoryCandidates(@RequestParam(defaultValue = "") String status,
                            @RequestHeader("Authorization") String authorization) {
        return memory.candidates(identities.require(authorization, CUSTOMERS), status);
    }

    @PostMapping("/customer/memory/candidates/{id}/decision")
    MemoryCandidateView decideMemory(@PathVariable String id, @Valid @RequestBody MemoryDecisionRequest request,
                                     @RequestHeader("Authorization") String authorization) {
        return memory.decide(id, request, identities.require(authorization, CUSTOMERS));
    }

    @GetMapping("/customer/memory/profile")
    Object memoryProfile(@RequestHeader("Authorization") String authorization) {
        return memory.profiles(identities.require(authorization, CUSTOMERS));
    }

    @PutMapping("/customer/memory/profile/{attributeCode}")
    UserProfileView correctMemoryProfile(@PathVariable String attributeCode,
                                         @Valid @RequestBody ProfileCorrectionRequest request,
                                         @RequestHeader("Authorization") String authorization) {
        return memory.correctProfile(attributeCode, request, identities.require(authorization, CUSTOMERS));
    }

    @DeleteMapping("/customer/memory/profile/{attributeCode}")
    Object deleteMemoryProfile(@PathVariable String attributeCode,
                               @RequestHeader("Authorization") String authorization) {
        boolean deleted = memory.deleteProfile(attributeCode, identities.require(authorization, CUSTOMERS));
        return Map.of("deleted", deleted);
    }

    // ---------- 商家管理员后台 ----------
    @GetMapping("/merchant/overview")
    MerchantOverview merchantOverview(@RequestHeader("Authorization") String authorization) {
        return merchants.overview(identities.require(authorization, MERCHANT_ADMINS));
    }

    @PutMapping("/merchant/store")
    MerchantStore updateMerchantStore(@Valid @RequestBody MerchantStoreUpdateRequest request,
                                      @RequestHeader("Authorization") String authorization) {
        return merchants.updateStore(identities.require(authorization, MERCHANT_ADMINS), request);
    }

    @PostMapping("/merchant/products")
    MerchantProductView createMerchantProduct(@Valid @RequestBody MerchantProductUpsertRequest request,
                                              @RequestHeader("Authorization") String authorization) {
        return merchants.createProduct(identities.require(authorization, MERCHANT_ADMINS), request);
    }

    @PutMapping("/merchant/products/{id}")
    MerchantProductView updateMerchantProduct(@PathVariable String id,
                                              @Valid @RequestBody MerchantProductUpsertRequest request,
                                              @RequestHeader("Authorization") String authorization) {
        return merchants.updateProduct(identities.require(authorization, MERCHANT_ADMINS), id, request);
    }

    @PutMapping("/merchant/products/{id}/status")
    MerchantProductView updateMerchantProductStatus(@PathVariable String id,
                                                     @Valid @RequestBody MerchantProductStatusRequest request,
                                                     @RequestHeader("Authorization") String authorization) {
        return merchants.updateStatus(identities.require(authorization, MERCHANT_ADMINS), id, request);
    }

    // ---------- 店铺客服 / 商城官方客服工作台 ----------
    @GetMapping("/staff/support/cases")
    Object staffCases(@RequestHeader("Authorization") String authorization) {
        return support.staffCases(identities.require(authorization, STAFF));
    }

    @GetMapping("/staff/support/cases/{id}/messages")
    Object staffMessages(@PathVariable String id, @RequestHeader("Authorization") String authorization) {
        return support.messages(id, identities.require(authorization, STAFF));
    }

    @GetMapping("/staff/support/cases/{id}/refund-assessment")
    RefundAssessmentView refundAssessment(@PathVariable String id,
                                          @RequestHeader("Authorization") String authorization) {
        SessionAccount staff = identities.require(authorization, STAFF);
        SupportCaseView item = support.staffCase(id, staff);
        if (!"HIGH_RISK_APPROVAL".equals(item.type()) || item.businessTaskId() == null)
            throw new IllegalArgumentException("该会话不是退款审核任务");
        return refunds.require(item.businessTaskId(), item.tenantId(), item.customerId());
    }

    @PostMapping("/staff/support/cases/{id}/claim")
    Object claim(@PathVariable String id, @RequestHeader("Authorization") String authorization) {
        return support.claim(id, identities.require(authorization, STAFF));
    }

    @PostMapping("/staff/support/cases/{id}/messages")
    Map<String, Object> staffReply(@PathVariable String id, @Valid @RequestBody SupportMessageRequest request,
                                   @RequestHeader("Authorization") String authorization) {
        support.staffReply(id, request.content(), identities.require(authorization, STAFF));
        return Map.of("success", true);
    }

    @PostMapping("/staff/support/cases/{id}/decision")
    Object decide(@PathVariable String id, @Valid @RequestBody StaffDecisionRequest request,
                  @RequestHeader("Authorization") String authorization) {
        return support.decide(id, request, identities.require(authorization, STAFF));
    }

    @PostMapping("/staff/support/cases/{id}/resolve")
    Object resolve(@PathVariable String id, @RequestHeader("Authorization") String authorization) {
        return support.resolve(id, identities.require(authorization, STAFF));
    }

    @GetMapping("/admin/metrics")
    Object metrics(@RequestHeader("Authorization") String authorization) {
        SessionAccount staff = identities.require(authorization, STAFF);
        return store.metrics(staff.tenantId());
    }

    @GetMapping("/staff/platform/balance")
    PlatformBalanceView platformBalance(@RequestHeader("Authorization") String authorization) {
        return commerce.platformBalance(identities.require(authorization, Set.of(AccountRole.OFFICIAL_AGENT)));
    }

    @GetMapping("/staff/platform/balance/ledger")
    Object platformBalanceLedger(@RequestHeader("Authorization") String authorization) {
        return commerce.platformLedger(identities.require(authorization, Set.of(AccountRole.OFFICIAL_AGENT)));
    }

    @GetMapping("/staff/knowledge/candidates")
    Object knowledgeCandidates(@RequestParam(defaultValue = "READY_FOR_REVIEW") String status,
                               @RequestHeader("Authorization") String authorization) {
        return knowledge.candidates(identities.require(authorization, Set.of(AccountRole.OFFICIAL_AGENT)), status);
    }

    @PostMapping("/staff/knowledge/candidates/{id}/decision")
    KnowledgeCandidateView decideKnowledge(@PathVariable String id,
                                           @Valid @RequestBody KnowledgeDecisionRequest request,
                                           @RequestHeader("Authorization") String authorization) {
        return knowledge.decide(id, request,
                identities.require(authorization, Set.of(AccountRole.OFFICIAL_AGENT)));
    }

    // ---------- 当前用户自己的 Agent Trace 图形回放 ----------
    @GetMapping("/observability/overview")
    Object observabilityOverview(@RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return observability.overview(customer.tenantId(), customer.id());
    }

    @GetMapping("/observability/traces")
    Object observabilityTraces(@RequestParam(defaultValue = "30") int limit,
                               @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return observability.summaries(customer.tenantId(), customer.id(), limit);
    }

    @GetMapping("/observability/traces/{traceId}")
    Object observabilityReplay(@PathVariable String traceId,
                               @RequestHeader("Authorization") String authorization) {
        SessionAccount customer = identities.require(authorization, CUSTOMERS);
        return observability.replay(customer.tenantId(), customer.id(), traceId)
                .orElseThrow(() -> new IllegalArgumentException("Trace 不存在或不属于当前用户"));
    }

    @GetMapping("/health")
    Object health() { return Map.of("status", "UP", "service", "hanaki-ecom-agent"); }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Object unauthenticated(AuthenticationException error) {
        return Map.of("error", "UNAUTHENTICATED", "message", error.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Object forbidden(SecurityException error) {
        return Map.of("error", "FORBIDDEN", "message", error.getMessage());
    }

    @ExceptionHandler(ModelCallException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Object modelUnavailable(ModelCallException error) {
        return Map.of("error", "MODEL_UNAVAILABLE", "message", error.getMessage());
    }

    @ExceptionHandler(RequestInProgressException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Object requestInProgress(RequestInProgressException error) {
        return Map.of("error", "REQUEST_IN_PROGRESS", "message", error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Object badRequest(IllegalArgumentException error) {
        return Map.of("error", "BAD_REQUEST", "message", error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Object invalidBody(MethodArgumentNotValidException error) {
        var fieldError = error.getBindingResult().getFieldErrors().stream().findFirst();
        String message = fieldError.map(item -> item.getField() + " " + item.getDefaultMessage())
                .orElse("请求参数校验失败");
        return Map.of("error", "VALIDATION_FAILED", "message", message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    Object uploadTooLarge(MaxUploadSizeExceededException error) {
        return Map.of("error", "PAYLOAD_TOO_LARGE", "message", "退款证据文件过大，请压缩后重新上传");
    }

    private AccountView accountView(SessionAccount account) {
        return new AccountView(account.id(), account.username(), account.displayName(), account.role(),
                account.tenantId(), account.storeId());
    }

    private String blankOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
