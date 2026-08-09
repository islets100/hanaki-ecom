package com.hanaki.ecom.agent;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 生成知识的版本依赖与失效边界；检索层仍通过 active=true 只读取有效版本。 */
@Service
public class KnowledgeLifecycleService {
    private final JdbcClient db;

    public KnowledgeLifecycleService(JdbcClient db) { this.db = db; }

    /** 当规则、Prompt 或知识快照升级时，所有依赖旧版本的生成知识立即转为 STALE。 */
    @Transactional
    public int invalidateChangedDependency(String tenantId, String dependencyType,
                                           String dependencyKey, String currentVersion) {
        return db.sql("update knowledge_doc set active=false,lifecycle_status='STALE' where tenant_id=:tenant " +
                        "and id in (select knowledge_doc_id from knowledge_dependency where tenant_id=:tenant " +
                        "and dependency_type=:type and dependency_key=:key and dependency_version<>:version) " +
                        "and lifecycle_status='ACTIVE'")
                .param("tenant", tenantId).param("type", dependencyType)
                .param("key", dependencyKey).param("version", currentVersion).update();
    }

    @Scheduled(fixedDelayString = "${agent.knowledge.expiry-scan-millis:60000}")
    public int expireDocuments() {
        return db.sql("update knowledge_doc set active=false,lifecycle_status='EXPIRED' " +
                        "where active=true and expires_at is not null and expires_at<=current_timestamp")
                .update();
    }
}
