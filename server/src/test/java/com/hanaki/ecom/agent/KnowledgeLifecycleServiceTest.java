package com.hanaki.ecom.agent;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeLifecycleServiceTest {
    private JdbcClient db;
    private KnowledgeLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:knowledge-lifecycle-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        lifecycle = new KnowledgeLifecycleService(db);
    }

    @Test
    void marksGeneratedKnowledgeStaleWhenSourceVersionChanges() {
        db.sql("insert into knowledge_doc(id,tenant_id,domain,title,content,version,active,lifecycle_status) " +
                "values('K1','tenant','PRE_SALE','问题','答案','v1',true,'ACTIVE')").update();
        db.sql("insert into knowledge_dependency(knowledge_doc_id,tenant_id,dependency_type,dependency_key," +
                "dependency_version,created_at) values('K1','tenant','RULE','business-rule','rule-v1',current_timestamp)")
                .update();

        assertThat(lifecycle.invalidateChangedDependency("tenant", "RULE", "business-rule", "rule-v2"))
                .isEqualTo(1);
        assertThat(db.sql("select lifecycle_status from knowledge_doc where id='K1'")
                .query(String.class).single()).isEqualTo("STALE");
        assertThat(db.sql("select active from knowledge_doc where id='K1'")
                .query(Boolean.class).single()).isFalse();
    }
}
