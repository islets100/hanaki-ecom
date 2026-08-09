package com.hanaki.ecom.agent;

import com.hanaki.ecom.domain.Domain.AgentDraft;
import com.hanaki.ecom.domain.Domain.EvaluationContextSnapshot;
import com.hanaki.ecom.domain.Domain.OrderSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Judge 之前的确定性硬校验。
 *
 * <p>Judge 仍然是概率模型，不能让它决定“泄露手机号是否可以少扣几分”或“虚构退款成功能否被
 * 其它优点抵消”。因此隐私、订单归属、写操作宣称、证据白名单和金额来源等不可妥协规则在 Java
 * 中先执行；任何一条违规都会把候选标成 REJECTED，不再参加模型评分。</p>
 */
@Service
public final class CandidateHardValidator {
    private static final Pattern ORDER_ID = Pattern.compile("\\bOD[A-Za-z0-9-]{4,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])");
    private static final Pattern MONEY = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d{1,2})?)\\s*元");

    public Validation validate(EvaluationContextSnapshot snapshot, AgentDraft draft) {
        List<String> checks = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        // 第一组：输出必须存在且尺寸可控，防止空答案或异常超长内容进入 Judge 上下文。
        if (draft == null || draft.answer() == null || draft.answer().isBlank()) violations.add("EMPTY_ANSWER");
        else checks.add("STRUCTURE_OK");
        if (draft != null && draft.answer() != null && draft.answer().length() > 2_000) violations.add("ANSWER_TOO_LONG");
        // 候选阶段没有写工具，任何“已经退款/取消/到账”的完成态宣称都是未经验证的事实。
        if (draft != null && contains(draft.answer(), "退款已成功", "退款已经成功", "补偿已发放", "已经取消订单",
                "订单已取消", "地址已修改", "已为您修改", "已经提交退款", "已到账"))
            violations.add("UNVERIFIED_WRITE_SUCCESS");
        if (draft != null && contains(draft.answer(), "AI_DASHSCOPE_API_KEY", "系统提示词", "tenantId=", "userId="))
            violations.add("SENSITIVE_OR_INTERNAL_DATA");
        if (draft != null && draft.answer() != null
                && (PHONE.matcher(draft.answer()).find() || ID_CARD.matcher(draft.answer()).find()))
            violations.add("UNMASKED_PERSONAL_DATA");

        // 第二组：引用只能来自本批次冻结的知识版本，不能引用生成过程中临时出现或模型虚构的文档。
        Set<String> allowedEvidence = new HashSet<>();
        snapshot.evidence().forEach(doc -> allowedEvidence.add(doc.title() + " " + doc.version()));
        if (draft != null && !allowedEvidence.containsAll(draft.evidence())) violations.add("UNKNOWN_EVIDENCE_REFERENCE");
        else checks.add("REFERENCES_VERIFIED");

        /*
         * 第三组：回答里出现的完整订单号必须属于冻结时的当前用户订单集合。这里同时兼容领域 record
         * 与快照反序列化后的 Map，但绝不相信模型自行提取或用户在问题中声称的订单归属。
         */
        List<?> orders = (List<?>) snapshot.businessFacts().getOrDefault("recentOrders", List.of());
        Set<String> allowedOrders = orders.stream().map(this::orderId).filter(value -> !value.isBlank())
                .map(String::toUpperCase).collect(java.util.stream.Collectors.toSet());
        if (draft != null && draft.answer() != null) {
            Matcher matcher = ORDER_ID.matcher(draft.answer());
            while (matcher.find()) if (!allowedOrders.contains(matcher.group().toUpperCase())) {
                violations.add("ORDER_NOT_IN_FROZEN_OWNER_SNAPSHOT"); break;
            }
        }
        if (violations.stream().noneMatch(v -> v.contains("ORDER_"))) checks.add("ORDER_OWNERSHIP_VERIFIED");
        // 第四组：工具审计中只要出现写工具标识，就说明候选隔离边界被破坏，必须整条淘汰。
        if (draft != null && draft.toolResults() != null && draft.toolResults().stream().anyMatch(this::writeToolReference))
            violations.add("WRITE_TOOL_USED_DURING_CANDIDATE_GENERATION");
        else checks.add("READ_ONLY_TOOL_SCOPE_VERIFIED");

        /*
         * 第五组：金额必须来自冻结订单或权威证据。BigDecimal 去除尾零后比较，使 100、100.0、
         * 100.00 表示同一数值，同时不使用 double，避免货币精度误判。
         */
        if (draft != null && draft.answer() != null && !allowedOrders.isEmpty()) {
            Set<String> allowedAmounts = orders.stream().map(this::amount).filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            Matcher money = MONEY.matcher(draft.answer());
            while (money.find()) {
                if (!allowedAmounts.contains(normalizeAmount(money.group(1))) && draft.evidence().isEmpty()) {
                    violations.add("AMOUNT_NOT_IN_FROZEN_SNAPSHOT_OR_EVIDENCE");
                    break;
                }
            }
        }
        checks.add("NO_WRITE_SIDE_EFFECT");
        // draft.safe 是生成层的快速结果；硬校验必须同时满足“无违规 + 生成层安全”才接受。
        return new Validation(violations.isEmpty() && draft != null && draft.safe(), List.copyOf(checks), List.copyOf(violations));
    }

    private boolean contains(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String orderId(Object value) {
        if (value instanceof OrderSummary order) return order.id();
        if (value instanceof java.util.Map<?, ?> map) {
            Object id = map.get("orderId");
            return id == null ? "" : String.valueOf(id);
        }
        return "";
    }

    private String amount(Object value) {
        Object amount = null;
        if (value instanceof OrderSummary order) amount = order.amount();
        else if (value instanceof Map<?, ?> map) amount = map.get("amount");
        return amount == null ? "" : normalizeAmount(String.valueOf(amount));
    }

    private String normalizeAmount(String value) {
        try { return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException ignored) { return value; }
    }

    private boolean writeToolReference(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("submit_refund") || normalized.contains("cancel_order")
                || normalized.contains("modify_address") || normalized.contains("issue_compensation")
                || normalized.contains("write=true");
    }

    public record Validation(boolean accepted, List<String> checks, List<String> violations) {
        /** 将通过项和违规项转换为稳定审计码；不把回答正文复制进审计字段。 */
        public List<String> auditEntries() {
            List<String> result = new ArrayList<>(checks);
            violations.forEach(value -> result.add("VIOLATION:" + value));
            return List.copyOf(result);
        }
    }
}
