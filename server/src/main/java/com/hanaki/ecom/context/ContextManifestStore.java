package com.hanaki.ecom.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * ContextManifest 的关系型审计存储。
 *
 * <p>主表记录一次组装的身份、版本、预算和哈希，明细表记录每个 ContextItem 是否被披露或裁剪。
 * 两张表都不保存原始 Prompt 内容；需要排障时通过 sourceId + version 回到相应发布源，既能重放
 * 又避免把用户消息、订单状态和政策全文复制成长期审计数据。高风险策略下保存失败由调用方按
 * fail-closed 处理。</p>
 */
@Repository
public class ContextManifestStore {
    private final JdbcClient db;
    private final ObjectMapper json;

    public ContextManifestStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public void save(ContextManifest manifest) {
        db.sql("insert into ai_context_manifest(id,tenant_id,conversation_id,run_id,trace_id,business_task_id," +
                        "agent_type,node_code,model_name,policy_version,prompt_versions_json,total_input_tokens," +
                        "reserved_output_tokens,safety_reserve_tokens,tool_protocol_tokens,trimmed_item_ids_json," +
                        "trim_reasons_json,security_events_json,assembled_context_hash,assemble_cost_ms,assembled_at) " +
                        "values(:id,:tenant,:conversation,:run,:trace,:task,:agent,:node,:model,:policy,:prompts," +
                        ":inputTokens,:outputTokens,:safetyTokens,:toolTokens,:trimmed,:reasons,:security,:hash,:cost,:at)")
                .param("id", manifest.manifestId()).param("tenant", manifest.tenantId())
                .param("conversation", manifest.conversationId()).param("run", manifest.runId())
                .param("trace", manifest.traceId()).param("task", blankToNull(manifest.businessTaskId()))
                .param("agent", manifest.agentType()).param("node", manifest.nodeCode().name())
                .param("model", manifest.modelName()).param("policy", manifest.policyVersion())
                .param("prompts", json(manifest.promptVersions())).param("inputTokens", manifest.totalInputTokens())
                .param("outputTokens", manifest.reservedOutputTokens())
                .param("safetyTokens", manifest.safetyReserveTokens())
                .param("toolTokens", manifest.toolProtocolTokens())
                .param("trimmed", json(manifest.trimmedItemIds())).param("reasons", json(manifest.trimReasons()))
                .param("security", json(manifest.securityEvents())).param("hash", manifest.assembledContextHash())
                .param("cost", manifest.assembleCostMillis()).param("at", manifest.assembledAt()).update();

        for (ContextManifestItem item : manifest.items()) {
            db.sql("insert into ai_context_manifest_item(manifest_id,item_id,section_type,source_id,source_type," +
                            "source_version,content_hash,token_count,priority,trust_level,sensitivity_level,load_reason," +
                            "trimmed,trim_reason,delivery) values(:manifest,:item,:section,:sourceId,:sourceType," +
                            ":version,:hash,:tokens,:priority,:trust,:sensitivity,:reason,:trimmed,:trimReason,:delivery)")
                    .param("manifest", manifest.manifestId()).param("item", item.itemId())
                    .param("section", item.sectionType().name()).param("sourceId", item.sourceId())
                    .param("sourceType", item.sourceType()).param("version", item.version())
                    .param("hash", item.contentHash()).param("tokens", item.tokenCount())
                    .param("priority", item.priority()).param("trust", item.trustLevel().name())
                    .param("sensitivity", item.sensitivityLevel().name()).param("reason", item.loadReason())
                    .param("trimmed", item.trimmed()).param("trimReason", blankToNull(item.trimReason()))
                    .param("delivery", item.delivery().name()).update();
        }
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("ContextManifest 序列化失败", error); }
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
