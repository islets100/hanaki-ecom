package com.hanaki.ecom.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.context.AssembledContext;
import com.hanaki.ecom.context.ContextAssemblyRequest;
import com.hanaki.ecom.context.ContextDelivery;
import com.hanaki.ecom.context.ContextGuard;
import com.hanaki.ecom.context.ContextItem;
import com.hanaki.ecom.context.ContextManifest;
import com.hanaki.ecom.context.ContextManifestItem;
import com.hanaki.ecom.context.ContextManifestStore;
import com.hanaki.ecom.context.ContextNode;
import com.hanaki.ecom.context.ContextPolicy;
import com.hanaki.ecom.context.ContextPolicyRegistry;
import com.hanaki.ecom.context.ContextSectionType;
import com.hanaki.ecom.context.ProgressiveSkillRegistry;
import com.hanaki.ecom.context.PromptRegistry;
import com.hanaki.ecom.context.SensitivityLevel;
import com.hanaki.ecom.context.SkillDisclosurePhase;
import com.hanaki.ecom.context.TrustLevel;
import com.hanaki.ecom.context.TrustedRequestContext;
import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.Intent;
import com.hanaki.ecom.domain.Domain.KnowledgeDoc;
import com.hanaki.ecom.security.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 企业级渐进式上下文组装器。
 *
 * <p>Graph 拓扑在启动时编译，但每次模型调用都重新执行本类：先解析节点级 ContextPolicy，再加载
 * 分层 Prompt、当前消息、阶段化 Memory、白名单业务事实、最终 RAG 证据和当前阶段 Skill；随后
 * 统一做租户校验、注入检测、脱敏、去重和预算分配，最后生成固定结构 Prompt 与 ContextManifest。
 * Graph State 只需要保存 selectedSkillKey、chunkId、任务状态等引用，不保存整份 Prompt 或所有
 * 工具 Schema。</p>
 *
 * <p>该类刻意不直接查询订单、Redis 或向量库。调用节点先通过各自 Provider/业务服务得到结构化
 * 数据，本类只接收统一请求并实施治理。这样无论未来数据源换成 MySQL、Elasticsearch 或 RPC，
 * 策略、安全边界、预算算法和审计结构都不会分叉。</p>
 */
@Service
public class ContextAssembler {
    private static final int DEFAULT_TOOL_PROTOCOL_OVERHEAD = 48;
    private static final Map<ContextSectionType, Integer> RENDER_ORDER = renderOrder();

    private final int configuredContextWindow;
    private final TokenBudgetEstimator tokens;
    private final ObjectMapper json;
    private final ContextPolicyRegistry policies;
    private final PromptRegistry prompts;
    private final ProgressiveSkillRegistry skills;
    private final ContextGuard guard;
    private final ContextManifestStore manifestStore;

    @Autowired
    public ContextAssembler(
            @Value("${agent.context.model-context-window:8192}") int configuredContextWindow,
            TokenBudgetEstimator tokens,
            ObjectMapper json,
            ContextPolicyRegistry policies,
            PromptRegistry prompts,
            ProgressiveSkillRegistry skills,
            ContextGuard guard,
            ContextManifestStore manifestStore) {
        if (configuredContextWindow <= 0) throw new IllegalArgumentException("模型上下文窗口必须大于 0");
        this.configuredContextWindow = configuredContextWindow;
        this.tokens = tokens;
        this.json = json;
        this.policies = policies;
        this.prompts = prompts;
        this.skills = skills;
        this.guard = guard;
        this.manifestStore = manifestStore;
    }

    /**
     * 兼容已有纯 Java 单元测试的构造器。它使用完整的新组装算法，但不访问数据库审计存储。
     * 旧的 generation 方法仍保留为兼容适配层，生产模型网关统一调用 {@link #assemble}。
     */
    ContextAssembler(int budgetTokens, TokenBudgetEstimator tokens, ObjectMapper json) {
        this.configuredContextWindow = budgetTokens + 2_000;
        this.tokens = tokens;
        this.json = json;
        this.policies = new ContextPolicyRegistry(budgetTokens, 1_200, 800);
        this.prompts = new PromptRegistry("test");
        this.skills = new ProgressiveSkillRegistry();
        this.guard = new ContextGuard(tokens);
        this.manifestStore = null;
    }

    /**
     * 按固定的、可审计的十五步流程组装一次模型上下文。
     *
     * <p>方法返回前已经完成 Skill 二次授权；调用方只能绑定 boundSkillKeys 对应的工具对象。若策略
     * 要求实时业务状态、完整 Schema 或平台安全规则而来源缺失/过期，方法直接失败，禁止模型凭
     * 历史消息猜测。可选 Memory/RAG 超预算时则按整个 ContextItem 删除，不会截断 JSON Schema
     * 或政策片段尾部。</p>
     */
    public AssembledContext assemble(ContextAssemblyRequest request) {
        long startedNanos = System.nanoTime();
        TrustedRequestContext trusted = request.trustedContext();
        ContextPolicy policy = policies.resolve(request.intent(), request.nodeCode());
        PromptRegistry.PromptBundle promptBundle = prompts.resolve(
                request.intent(), request.nodeCode(), request.candidateVariant());

        ProgressiveSkillRegistry.AuthorizedSkillSet authorized = skills.authorize(
                request.intent(), policy, request.selectedSkillKey(), request.skillDisclosurePhase(),
                trusted.riskLevel());

        // Provider 加载可以在未来并行化；这里先保持确定性收集顺序，最终结果还会按分区和优先级排序。
        List<ContextItem> loaded = new ArrayList<>();
        loaded.addAll(promptItems(promptBundle, trusted));
        loaded.addAll(currentMessageItems(request, policy));
        loaded.addAll(memoryItems(request, policy));
        loaded.addAll(businessItems(request, policy));
        loaded.addAll(evidenceItems(request, policy));
        loaded.addAll(skillItems(request, policy, authorized));
        loaded.addAll(candidateItems(request, policy));

        ContextGuard.GuardResult guarded = guard.inspect(trusted, policy, loaded);
        List<ContextItem> deduplicated = deduplicate(guarded.accepted());
        verifyRequiredSections(policy, deduplicated);

        int modelWindow = request.requestedContextWindow() > 0
                ? Math.min(configuredContextWindow, request.requestedContextWindow()) : configuredContextWindow;
        int toolTokens = deduplicated.stream()
                .filter(item -> item.delivery() == ContextDelivery.TOOL_PROTOCOL)
                .mapToInt(ContextItem::estimatedTokens).sum();
        if (!authorized.schemas().isEmpty()) toolTokens += DEFAULT_TOOL_PROTOCOL_OVERHEAD;
        int promptBudget = Math.min(policy.maxInputTokens(), modelWindow
                - policy.outputReserveTokens() - policy.safetyReserveTokens() - toolTokens);
        if (promptBudget <= 0)
            throw new IllegalStateException("模型窗口扣除输出、安全和完整工具 Schema 后没有可用输入预算");

        BudgetSelection selection = selectWithinBudget(deduplicated, promptBudget);
        String systemPrompt = render(selection.selected(), ContextDelivery.SYSTEM_MESSAGE);
        String userPrompt = render(selection.selected(), ContextDelivery.USER_MESSAGE);
        int renderedTokens = tokens.estimate(systemPrompt) + tokens.estimate(userPrompt);
        if (renderedTokens > promptBudget) {
            // 标题等渲染开销在 Item 估算之外，进行一次只删除可选项的保守收敛，必需项绝不静默丢失。
            selection = shrinkForRenderOverhead(selection, renderedTokens - promptBudget, promptBudget);
            systemPrompt = render(selection.selected(), ContextDelivery.SYSTEM_MESSAGE);
            userPrompt = render(selection.selected(), ContextDelivery.USER_MESSAGE);
            renderedTokens = tokens.estimate(systemPrompt) + tokens.estimate(userPrompt);
            if (renderedTokens > promptBudget)
                throw new IllegalStateException("必需上下文加固定渲染结构超过节点 Token 预算，应拆分模型节点");
        }

        String assembledHash = CacheKeyBuilder.digest(systemPrompt + "\u001e" + userPrompt + "\u001e"
                + authorized.skillKeys());
        ContextManifest manifest = manifest(request, policy, promptBundle, loaded, guarded,
                selection, renderedTokens, toolTokens, assembledHash, startedNanos);
        persistManifest(manifest, policy.failClosed());
        return new AssembledContext(systemPrompt, userPrompt, authorized.skillKeys(), manifest);
    }

    private List<ContextItem> promptItems(PromptRegistry.PromptBundle bundle, TrustedRequestContext trusted) {
        List<ContextItem> result = new ArrayList<>();
        for (PromptRegistry.PromptLayer layer : bundle.layers()) {
            result.add(item("prompt:" + layer.promptKey(), layer.section(), layer.content(), "PROMPT_REGISTRY",
                    layer.promptKey(), "", TrustLevel.TRUSTED_PLATFORM, SensitivityLevel.INTERNAL,
                    layer.priority(), layer.required(), true, ContextDelivery.SYSTEM_MESSAGE,
                    layer.version(), "当前节点分层 System Prompt", Map.of()));
        }
        return result;
    }

    private List<ContextItem> currentMessageItems(ContextAssemblyRequest request, ContextPolicy policy) {
        if (!policy.allows(ContextSectionType.CURRENT_USER_MESSAGE)) return List.of();
        return List.of(item("request:current", ContextSectionType.CURRENT_USER_MESSAGE,
                request.currentMessage(), "USER_REQUEST", request.trustedContext().runId(),
                request.trustedContext().tenantId(), TrustLevel.UNTRUSTED_USER, SensitivityLevel.PERSONAL,
                100, policy.requires(ContextSectionType.CURRENT_USER_MESSAGE), false,
                ContextDelivery.USER_MESSAGE, "current", "本轮用户问题", Map.of()));
    }

    private List<ContextItem> memoryItems(ContextAssemblyRequest request, ContextPolicy policy) {
        if (request.recentMessages().isEmpty()) return List.of();
        List<String> recent = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        List<String> episodes = new ArrayList<>();
        List<String> profiles = new ArrayList<>();
        for (String value : request.recentMessages()) {
            if (value == null || value.isBlank()) continue;
            if (value.startsWith("会话摘要")) summaries.add(value);
            else if (value.startsWith("相关历史偏好") || value.startsWith("相关情景记忆")) episodes.add(value);
            else if (value.startsWith("已确认用户画像")) profiles.add(value);
            else recent.add(value);
        }

        List<ContextItem> result = new ArrayList<>();
        if (policy.allows(ContextSectionType.RECENT_MESSAGE)) {
            List<String> limited = tail(recent, policy.maxRecentMessages());
            for (int i = 0; i < limited.size(); i++)
                result.add(dataItem("memory:recent:" + i, ContextSectionType.RECENT_MESSAGE, limited.get(i),
                        request, TrustLevel.UNTRUSTED_USER, 45, "节点工作记忆"));
        }
        if (policy.allows(ContextSectionType.CONVERSATION_SUMMARY)) {
            for (int i = 0; i < summaries.size(); i++)
                result.add(dataItem("memory:summary:" + i, ContextSectionType.CONVERSATION_SUMMARY,
                        summaries.get(i), request, TrustLevel.DERIVED_SUMMARY, 72, "当前领域会话摘要"));
        }
        if (policy.allows(ContextSectionType.EPISODIC_MEMORY)) {
            List<String> limited = episodes.stream().limit(policy.maxEpisodicMemories()).toList();
            for (int i = 0; i < limited.size(); i++)
                result.add(dataItem("memory:episode:" + i, ContextSectionType.EPISODIC_MEMORY,
                        limited.get(i), request, TrustLevel.DERIVED_SUMMARY, 35, "任务相关情景记忆"));
        }
        if (policy.allowLongTermProfile() && policy.allows(ContextSectionType.USER_PROFILE)) {
            for (int i = 0; i < profiles.size(); i++)
                result.add(dataItem("memory:profile:" + i, ContextSectionType.USER_PROFILE,
                        profiles.get(i), request, TrustLevel.USER_CONFIRMED, 30, "Agent 允许的已确认画像字段投影"));
        }
        return List.copyOf(result);
    }

    private ContextItem dataItem(String id, ContextSectionType section, String content,
                                 ContextAssemblyRequest request, TrustLevel trust, int priority, String reason) {
        return item(id, section, content, "MEMORY_PROVIDER", id,
                request.trustedContext().tenantId(), trust, SensitivityLevel.PERSONAL,
                priority, false, false, ContextDelivery.USER_MESSAGE,
                "memory-v2", reason, Map.of());
    }

    private List<ContextItem> businessItems(ContextAssemblyRequest request, ContextPolicy policy) {
        if (!policy.allows(ContextSectionType.BUSINESS_STATE)) return List.of();
        Map<String, Object> projected = new LinkedHashMap<>();
        request.businessFacts().forEach((key, value) -> {
            if (policy.allowedBusinessFields().contains(key)) projected.put(key, value);
        });
        if (projected.isEmpty() && !policy.requires(ContextSectionType.BUSINESS_STATE)) return List.of();
        if (projected.isEmpty())
            throw new IllegalStateException("当前节点要求实时业务事实，但字段白名单投影后为空");

        Instant fetchedAt = instant(request.businessFacts().get("fetchedAt"), Instant.now());
        String source = String.valueOf(request.businessFacts().getOrDefault("source", "ECOMMERCE_STORE"));
        String version = String.valueOf(request.businessFacts().getOrDefault("version", "realtime"));
        String content = json(projected, "业务状态投影序列化失败");
        return List.of(item("business:frozen-state", ContextSectionType.BUSINESS_STATE, content,
                "BUSINESS_STATE_LOADER", source, request.trustedContext().tenantId(),
                TrustLevel.TRUSTED_BUSINESS, SensitivityLevel.PERSONAL, 94,
                policy.requires(ContextSectionType.BUSINESS_STATE), true, ContextDelivery.USER_MESSAGE,
                version, "当前节点白名单业务事实", Map.of("fetchedAt", fetchedAt.toString()),
                fetchedAt, fetchedAt.plus(5, ChronoUnit.MINUTES)));
    }

    private List<ContextItem> evidenceItems(ContextAssemblyRequest request, ContextPolicy policy) {
        if (!policy.allows(ContextSectionType.RAG_EVIDENCE) || policy.maxRagChunks() == 0) return List.of();
        List<KnowledgeDoc> evidence = EvidencePackBuilder.build(request.evidence(), policy.maxRagChunks());
        List<ContextItem> result = new ArrayList<>();
        for (KnowledgeDoc doc : evidence) {
            String ownerTenant = doc.tenantId() == null ? "" : doc.tenantId().strip();
            // 平台知识由租户范围内的 RAG Provider 显式继承；进入最终 Guard 时按共享证据处理。
            // 其他商家 tenantId 保持不变，仍会被 ContextGuard 严格拒绝，不能借此跨租户注入。
            boolean sharedKnowledge = "public".equals(ownerTenant)
                    || TenantService.PLATFORM_TENANT_ID.equals(ownerTenant);
            String tenant = ownerTenant.isBlank() || sharedKnowledge ? "" : ownerTenant;
            String content = "chunkId=" + doc.id() + "\n标题=" + doc.title() + "\n版本=" + doc.version()
                    + "\n内容=" + doc.content();
            result.add(item("rag:" + doc.id(), ContextSectionType.RAG_EVIDENCE, content,
                    "RAG_EVIDENCE_PROVIDER", doc.id(), tenant, TrustLevel.VERIFIED_KNOWLEDGE,
                    SensitivityLevel.INTERNAL, 80 + (int) Math.round(Math.min(10, doc.score() * 10)),
                    false, true, ContextDelivery.USER_MESSAGE, doc.version(),
                    "RRF/Rerank 后的最终证据", Map.of(
                            "score", doc.score(),
                            "ownerTenant", ownerTenant,
                            "sharedKnowledge", sharedKnowledge)));
        }
        return List.copyOf(result);
    }

    private List<ContextItem> skillItems(ContextAssemblyRequest request, ContextPolicy policy,
                                         ProgressiveSkillRegistry.AuthorizedSkillSet authorized) {
        List<ContextItem> result = new ArrayList<>();
        if (request.skillDisclosurePhase() == SkillDisclosurePhase.CARD
                && policy.allows(ContextSectionType.SKILL_CARD)) {
            for (ProgressiveSkillRegistry.SkillCard card : skills.cards(request.intent(), policy)) {
                result.add(item("skill-card:" + card.skillKey(), ContextSectionType.SKILL_CARD,
                        json(card, "Skill 能力卡序列化失败"), "SKILL_REGISTRY", card.skillKey(), "",
                        TrustLevel.TRUSTED_PLATFORM, SensitivityLevel.INTERNAL, 52,
                        false, true, ContextDelivery.USER_MESSAGE, "skill-card-v3",
                        "第一阶段仅披露能力概要", Map.of("risk", card.riskLevel().name())));
            }
        }
        if (request.skillDisclosurePhase() == SkillDisclosurePhase.SCHEMA) {
            for (ProgressiveSkillRegistry.SkillSchema schema : authorized.schemas()) {
                /*
                 * 完整 Schema 只通过工具协议交付，content 用于预算估算和哈希审计，渲染器不会把它
                 * 复制到 userPrompt。preserveWhole + required 保证预算不足时整次调用失败而不是截断 JSON。
                 */
                result.add(item("skill-schema:" + schema.skillKey(), ContextSectionType.SKILL_SCHEMA,
                        json(schema, "Skill Schema 序列化失败"), "SKILL_REGISTRY", schema.skillKey(), "",
                        TrustLevel.TRUSTED_PLATFORM, SensitivityLevel.INTERNAL, 99,
                        true, true, ContextDelivery.TOOL_PROTOCOL, schema.version(),
                        "第二阶段授权后的完整工具 Schema", Map.of("toolName", schema.toolName())));
            }
            if (authorized.primaryCard() != null && policy.allows(ContextSectionType.SKILL_CARD)) {
                result.add(item("skill-card:selected", ContextSectionType.SKILL_CARD,
                        json(authorized.primaryCard(), "已选 Skill 卡片序列化失败"), "SKILL_REGISTRY",
                        authorized.primaryCard().skillKey(), "", TrustLevel.TRUSTED_PLATFORM,
                        SensitivityLevel.INTERNAL, 58, false, true, ContextDelivery.USER_MESSAGE,
                        "skill-card-v3", "说明本次实际授权能力", Map.of("selected", true)));
            }
        }
        return List.copyOf(result);
    }

    private List<ContextItem> candidateItems(ContextAssemblyRequest request, ContextPolicy policy) {
        if (!policy.allows(ContextSectionType.MODEL_CANDIDATE)) return List.of();
        List<ContextItem> result = new ArrayList<>();
        for (AgentDraft candidate : request.candidates()) {
            if (candidate == null || candidate.candidateId() == null) continue;
            result.add(item("candidate:" + candidate.candidateId(), ContextSectionType.MODEL_CANDIDATE,
                    json(candidate, "Judge 候选序列化失败"), "CANDIDATE_GRAPH", candidate.candidateId(),
                    request.trustedContext().tenantId(), TrustLevel.UNTRUSTED_EXTERNAL,
                    SensitivityLevel.INTERNAL, 92, true, true, ContextDelivery.USER_MESSAGE,
                    "candidate-v2", "Judge 本次候选白名单", Map.of()));
        }
        return List.copyOf(result);
    }

    private ContextItem item(String id, ContextSectionType section, String content, String sourceType,
                             String sourceId, String tenantId, TrustLevel trust,
                             SensitivityLevel sensitivity, int priority, boolean required,
                             boolean preserveWhole, ContextDelivery delivery, String version,
                             String loadReason, Map<String, Object> metadata) {
        Instant now = Instant.now();
        return item(id, section, content, sourceType, sourceId, tenantId, trust, sensitivity, priority,
                required, preserveWhole, delivery, version, loadReason, metadata, now, null);
    }

    private ContextItem item(String id, ContextSectionType section, String content, String sourceType,
                             String sourceId, String tenantId, TrustLevel trust,
                             SensitivityLevel sensitivity, int priority, boolean required,
                             boolean preserveWhole, ContextDelivery delivery, String version,
                             String loadReason, Map<String, Object> metadata,
                             Instant fetchedAt, Instant expiresAt) {
        String safeContent = content == null ? "" : content;
        return new ContextItem(id, section, safeContent, sourceType, sourceId, tenantId, trust,
                sensitivity, priority, tokens.estimate(safeContent), required, preserveWhole, delivery,
                fetchedAt, expiresAt, version, CacheKeyBuilder.digest(safeContent), loadReason, metadata);
    }

    /**
     * 相同分区 + 内容哈希只保留优先级最高的一项。sourceId 不参与去重，避免同一段 Memory 因来自
     * Redis 和数据库各占一次预算；Manifest 仍能看到被去重项及 DEDUPLICATED 原因。
     */
    private List<ContextItem> deduplicate(List<ContextItem> values) {
        Map<String, ContextItem> selected = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparingInt(ContextItem::priority).reversed()
                        .thenComparing(ContextItem::itemId))
                .forEach(item -> selected.putIfAbsent(item.sectionType() + ":" + item.contentHash(), item));
        return selected.values().stream().sorted(itemOrder()).toList();
    }

    private void verifyRequiredSections(ContextPolicy policy, List<ContextItem> items) {
        Set<ContextSectionType> present = items.stream().map(ContextItem::sectionType)
                .collect(java.util.stream.Collectors.toSet());
        Set<ContextSectionType> missing = new HashSet<>(policy.requiredSections());
        missing.removeAll(present);
        if (!missing.isEmpty()) throw new IllegalStateException("节点缺少必需上下文分区：" + missing);
    }

    private BudgetSelection selectWithinBudget(List<ContextItem> values, int promptBudget) {
        List<ContextItem> toolItems = values.stream()
                .filter(item -> item.delivery() == ContextDelivery.TOOL_PROTOCOL).toList();
        List<ContextItem> promptItems = values.stream()
                .filter(item -> item.delivery() != ContextDelivery.TOOL_PROTOCOL).toList();
        List<ContextItem> selected = new ArrayList<>(toolItems);
        List<Trimmed> trimmed = new ArrayList<>();
        int used = 0;

        // P0 preserveWhole 项先硬保留，避免可截断的长问题抢占平台安全、业务事实或输出协议空间。
        List<ContextItem> mandatoryWhole = promptItems.stream()
                .filter(ContextItem::required).filter(ContextItem::preserveWhole).sorted(itemOrder()).toList();
        for (ContextItem item : mandatoryWhole) {
            if (used + item.estimatedTokens() > promptBudget)
                throw new IllegalStateException("完整必需上下文超过节点预算：" + item.itemId());
            selected.add(item);
            used += item.estimatedTokens();
        }

        // 当前问题等 P0 数据不可删除，但允许在字符边界做有记录的保守截断；Schema 和事实不走此分支。
        for (ContextItem item : promptItems.stream().filter(ContextItem::required)
                .filter(value -> !value.preserveWhole()).sorted(itemOrder()).toList()) {
            int available = promptBudget - used;
            if (available <= 0) throw new IllegalStateException("必需用户输入没有可用 Token 预算");
            if (item.estimatedTokens() <= available) {
                selected.add(item);
                used += item.estimatedTokens();
            } else {
                String shortened = tokens.truncate(item.content(), available);
                if (shortened.isBlank()) throw new IllegalStateException("必需用户输入无法在预算内保留");
                ContextItem truncated = item.withContent(shortened, tokens.estimate(shortened),
                        CacheKeyBuilder.digest(shortened), merge(item.metadata(), "truncated", true));
                selected.add(truncated);
                used += truncated.estimatedTokens();
                trimmed.add(new Trimmed(item, "MANDATORY_TRUNCATED_AT_TOKEN_BOUNDARY"));
            }
        }

        // P1-P3 项只按整项加入。RAG Chunk、Memory 和能力卡不做尾部截断，避免丢掉政策例外或 JSON 尾部。
        List<ContextItem> optional = promptItems.stream().filter(item -> !item.required())
                .sorted(Comparator.comparingInt(ContextItem::priority).reversed()
                        .thenComparing(item -> RENDER_ORDER.getOrDefault(item.sectionType(), 999))
                        .thenComparing(ContextItem::itemId)).toList();
        for (ContextItem item : optional) {
            if (used + item.estimatedTokens() <= promptBudget) {
                selected.add(item);
                used += item.estimatedTokens();
            } else trimmed.add(new Trimmed(item, "TOKEN_BUDGET_EXCEEDED"));
        }
        return new BudgetSelection(selected.stream().sorted(itemOrder()).toList(), List.copyOf(trimmed));
    }

    private BudgetSelection shrinkForRenderOverhead(BudgetSelection original, int excess, int promptBudget) {
        List<ContextItem> values = new ArrayList<>(original.selected());
        List<Trimmed> trimmed = new ArrayList<>(original.trimmed());
        List<ContextItem> removable = values.stream()
                .filter(item -> !item.required() && item.delivery() != ContextDelivery.TOOL_PROTOCOL)
                .sorted(Comparator.comparingInt(ContextItem::priority)
                        .thenComparing(Comparator.comparingInt(ContextItem::estimatedTokens).reversed()))
                .toList();
        int released = 0;
        for (ContextItem item : removable) {
            values.remove(item);
            trimmed.add(new Trimmed(item, "FIXED_RENDER_OVERHEAD"));
            released += item.estimatedTokens();
            if (released >= excess) break;
        }
        return new BudgetSelection(values.stream().sorted(itemOrder()).toList(), List.copyOf(trimmed));
    }

    private String render(List<ContextItem> items, ContextDelivery delivery) {
        Map<ContextSectionType, List<ContextItem>> grouped = new EnumMap<>(ContextSectionType.class);
        items.stream().filter(item -> item.delivery() == delivery)
                .forEach(item -> grouped.computeIfAbsent(item.sectionType(), ignored -> new ArrayList<>()).add(item));
        StringBuilder result = new StringBuilder();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey(
                        Comparator.comparingInt(section -> RENDER_ORDER.getOrDefault(section, 999))))
                .forEach(entry -> {
                    result.append('[').append(title(entry.getKey())).append("]\n");
                    entry.getValue().stream().sorted(itemOrder()).forEach(item ->
                            result.append(item.content()).append("\n"));
                    result.append("\n");
                });
        return result.toString().stripTrailing();
    }

    private ContextManifest manifest(ContextAssemblyRequest request, ContextPolicy policy,
                                     PromptRegistry.PromptBundle promptBundle, List<ContextItem> loaded,
                                     ContextGuard.GuardResult guarded, BudgetSelection selection,
                                     int renderedTokens, int toolTokens, String assembledHash,
                                     long startedNanos) {
        Map<String, String> reasons = new LinkedHashMap<>();
        guarded.rejected().forEach(value -> reasons.put(value.item().itemId(), value.reason()));
        selection.trimmed().forEach(value -> reasons.put(value.item().itemId(), value.reason()));
        Set<String> selectedIds = selection.selected().stream().map(ContextItem::itemId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, ContextItem> guardedById = new HashMap<>();
        guarded.accepted().forEach(item -> guardedById.put(item.itemId(), item));
        Map<String, ContextItem> selectedById = new HashMap<>();
        selection.selected().forEach(item -> selectedById.put(item.itemId(), item));
        List<ContextManifestItem> manifestItems = new ArrayList<>();
        for (ContextItem item : loaded) {
            boolean trimmed = !selectedIds.contains(item.itemId());
            // 被实际披露的项目记录 Guard/脱敏/边界截断后的哈希和 Token；未披露项目记录 Guard 后
            // 的候选哈希。这样 Manifest 能精确回放“模型真正看到的版本”，而不是原始输入版本。
            ContextItem audited = selectedById.getOrDefault(item.itemId(),
                    guardedById.getOrDefault(item.itemId(), item));
            manifestItems.add(new ContextManifestItem(item.itemId(), item.sectionType(), item.sourceId(),
                    item.sourceType(), item.version(), audited.contentHash(), audited.estimatedTokens(), item.priority(),
                    item.trustLevel(), item.sensitivityLevel(), item.loadReason(), trimmed,
                    reasons.getOrDefault(item.itemId(), trimmed ? "DEDUPLICATED" : ""), item.delivery()));
        }
        // 当前问题被边界截断时，原 itemId 仍被选择；显式把 Manifest 标记为裁剪，避免审计误以为全文进入模型。
        selection.trimmed().stream().filter(value -> value.reason().startsWith("MANDATORY_TRUNCATED"))
                .forEach(value -> {
                    for (int i = 0; i < manifestItems.size(); i++) {
                        ContextManifestItem item = manifestItems.get(i);
                        if (item.itemId().equals(value.item().itemId()))
                            manifestItems.set(i, new ContextManifestItem(item.itemId(), item.sectionType(),
                                    item.sourceId(), item.sourceType(), item.version(), item.contentHash(),
                                    item.tokenCount(), item.priority(), item.trustLevel(), item.sensitivityLevel(),
                                    item.loadReason(), true, value.reason(), item.delivery()));
                    }
                });
        List<String> trimmedIds = reasons.keySet().stream().toList();
        List<String> trimReasons = reasons.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue()).toList();
        TrustedRequestContext trusted = request.trustedContext();
        return new ContextManifest(UUID.randomUUID().toString(), trusted.tenantId(), trusted.conversationId(),
                trusted.runId(), trusted.traceId(), trusted.businessTaskId(), policy.agentType(),
                request.nodeCode(), request.modelName(), policy.policyVersion(), promptBundle.versions(),
                List.copyOf(manifestItems), renderedTokens + toolTokens, policy.outputReserveTokens(),
                policy.safetyReserveTokens(), toolTokens, trimmedIds, trimReasons,
                guarded.securityEvents(), assembledHash, Instant.now(),
                (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private void persistManifest(ContextManifest manifest, boolean failClosed) {
        if (manifestStore == null) return;
        try { manifestStore.save(manifest); }
        catch (RuntimeException error) {
            if (failClosed) throw new IllegalStateException("ContextManifest 审计保存失败，节点按策略关闭", error);
        }
    }

    private Comparator<ContextItem> itemOrder() {
        return Comparator.comparingInt((ContextItem item) -> RENDER_ORDER.getOrDefault(item.sectionType(), 999))
                .thenComparing(Comparator.comparingInt(ContextItem::priority).reversed())
                .thenComparing(ContextItem::itemId);
    }

    private String title(ContextSectionType section) {
        return switch (section) {
            case PLATFORM_SAFETY_RULE -> "平台安全规则";
            case AGENT_SYSTEM_PROMPT -> "当前Agent职责";
            case TENANT_INSTRUCTION -> "租户已审核配置";
            case NODE_INSTRUCTION -> "当前节点任务";
            case BUSINESS_STATE -> "可信业务事实";
            case CONVERSATION_SUMMARY -> "会话摘要";
            case RECENT_MESSAGE -> "最近消息";
            case EPISODIC_MEMORY -> "相关情景记忆";
            case USER_PROFILE -> "允许的用户画像";
            case RAG_EVIDENCE -> "知识证据";
            case SKILL_CARD -> "当前可用能力";
            case SKILL_SCHEMA -> "完整工具Schema";
            case TOOL_RESULT -> "工具结果";
            case MODEL_CANDIDATE -> "候选答案";
            case CURRENT_USER_MESSAGE -> "用户输入";
            case OUTPUT_CONSTRAINT -> "输出要求";
        };
    }

    private static Map<ContextSectionType, Integer> renderOrder() {
        Map<ContextSectionType, Integer> order = new EnumMap<>(ContextSectionType.class);
        ContextSectionType[] sequence = {
                ContextSectionType.PLATFORM_SAFETY_RULE, ContextSectionType.AGENT_SYSTEM_PROMPT,
                ContextSectionType.TENANT_INSTRUCTION, ContextSectionType.NODE_INSTRUCTION,
                ContextSectionType.BUSINESS_STATE, ContextSectionType.CONVERSATION_SUMMARY,
                ContextSectionType.RECENT_MESSAGE, ContextSectionType.EPISODIC_MEMORY,
                ContextSectionType.USER_PROFILE, ContextSectionType.RAG_EVIDENCE,
                ContextSectionType.SKILL_CARD, ContextSectionType.SKILL_SCHEMA,
                ContextSectionType.TOOL_RESULT, ContextSectionType.MODEL_CANDIDATE,
                ContextSectionType.CURRENT_USER_MESSAGE, ContextSectionType.OUTPUT_CONSTRAINT
        };
        for (int i = 0; i < sequence.length; i++) order.put(sequence[i], i);
        return Map.copyOf(order);
    }

    private List<String> tail(List<String> values, int limit) {
        if (limit <= 0 || values.isEmpty()) return List.of();
        return List.copyOf(values.subList(Math.max(0, values.size() - limit), values.size()));
    }

    private Map<String, Object> merge(Map<String, Object> source, String key, Object value) {
        Map<String, Object> result = new HashMap<>(source);
        result.put(key, value);
        return Map.copyOf(result);
    }

    private Instant instant(Object value, Instant fallback) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof String string) {
            try { return Instant.parse(string); }
            catch (Exception ignored) { return fallback; }
        }
        return fallback;
    }

    private String json(Object value, String message) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException(message, error); }
    }

    private record Trimmed(ContextItem item, String reason) {}
    private record BudgetSelection(List<ContextItem> selected, List<Trimmed> trimmed) {}

    // -------------------------------------------------------------------------
    // 旧 API 兼容层：避免现有调用者和回归测试在迁移期间失效。生产网关已经改用 assemble。
    // -------------------------------------------------------------------------

    public String system(Intent intent, int variant) {
        PromptRegistry.PromptBundle bundle = prompts.resolve(intent, ContextNode.ANSWER_GENERATION, variant);
        return bundle.layers().stream().map(layer -> "[" + title(layer.section()) + "]\n" + layer.content())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    /**
     * 历史生成接口使用独立的紧凑兼容预算。它保持“事实和证据不能被超长历史挤掉”的旧契约；
     * 新代码必须使用 assemble，以获得节点策略、安全守卫、Skill 两阶段披露和 Manifest。
     */
    public String generation(Intent intent, String message, String rewrittenQuery,
                             List<String> memories, List<KnowledgeDoc> evidence,
                             Map<String, Object> businessFacts) {
        int budget = Math.max(1, policies.resolve(intent, ContextNode.ANSWER_GENERATION).maxInputTokens());
        List<LegacyItem> items = new ArrayList<>();
        // 兼容层先硬保留安全、事实与证据，再用剩余预算保留尽可能多的当前问题。
        items.add(new LegacyItem("【当前任务】\n" + safe(message), 80, false));
        items.add(new LegacyItem("【不可违反的输出约束】\n不得承诺工具未返回的状态、金额或时效。", 99, true));
        items.add(new LegacyItem("【检索问题】\n" + safe(rewrittenQuery), 95, true));
        items.add(new LegacyItem("【本次冻结的真实业务状态】\n" + json(
                businessFacts == null ? Map.of() : businessFacts, "业务事实上下文序列化失败"), 94, true));
        List<KnowledgeDoc> pack = EvidencePackBuilder.build(evidence, 8);
        items.add(new LegacyItem("【权威知识证据】\n" + pack.stream()
                .map(doc -> "[" + doc.id() + "] " + doc.title() + " " + doc.version() + "：" + doc.content())
                .reduce((a, b) -> a + "\n" + b).orElse("（无可用证据）"), 90, true));
        if (memories != null) memories.stream().filter(value -> value != null && !value.isBlank()).distinct()
                .forEach(value -> items.add(new LegacyItem("【会话与记忆（低优先级）】\n" + value, 30, false)));
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (LegacyItem item : items.stream().sorted(Comparator.comparingInt(LegacyItem::priority).reversed()).toList()) {
            int available = budget - used;
            if (available <= 0) continue;
            String selected = tokens.estimate(item.content()) <= available ? item.content()
                    : (item.preserveWhole() ? "" : tokens.truncate(item.content(), available));
            if (selected.isBlank()) continue;
            result.append(selected).append("\n\n");
            used += tokens.estimate(selected);
        }
        return result.toString().stripTrailing();
    }

    public Map<String, Object> policy(Intent intent) {
        ContextPolicy policy = policies.resolve(intent, ContextNode.ANSWER_GENERATION);
        return Map.of("intent", intent.name(), "policyId", policy.policyId(),
                "policyVersion", policy.policyVersion(), "allowedSkills", policy.allowedSkillKeys(),
                "allowedSections", policy.allowedSections(), "allowedBusinessFields", policy.allowedBusinessFields(),
                "modelContextWindow", configuredContextWindow, "availableInputTokens", policy.maxInputTokens(),
                "outputReserveTokens", policy.outputReserveTokens(), "safetyReserveTokens", policy.safetyReserveTokens());
    }

    private String safe(String value) { return value == null ? "" : value; }
    private record LegacyItem(String content, int priority, boolean preserveWhole) {}
}
