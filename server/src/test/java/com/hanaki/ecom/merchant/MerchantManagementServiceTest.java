package com.hanaki.ecom.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.MerchantProductStatusRequest;
import com.hanaki.ecom.domain.Domain.MerchantProductUpsertRequest;
import com.hanaki.ecom.domain.Domain.MerchantStoreUpdateRequest;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantManagementServiceTest {
    private JdbcClient db;
    private MerchantManagementService service;
    private SessionAccount admin;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:merchant-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), dataSource);
        db = JdbcClient.create(dataSource);
        db.sql("insert into merchant_store(id,tenant_id,name,logo_text,description,service_score," +
                        "fulfillment_score,location,created_at) values('STORE-ONE','merchant-one','测试店'," +
                        "'测','介绍',5,5,'上海',current_timestamp)").update();
        service = new MerchantManagementService(db, new ObjectMapper());
        admin = new SessionAccount("admin", "merchant-one", "owner", "店主",
                AccountRole.STORE_ADMIN, "STORE-ONE");
    }

    @Test
    void adminCanMaintainStoreAndFullProductLifecycle() {
        assertThat(service.updateStore(admin,
                new MerchantStoreUpdateRequest("新店名", "新", "新的店铺介绍", "杭州")).name())
                .isEqualTo("新店名");

        var created = service.createProduct(admin, new MerchantProductUpsertRequest(
                "测试商品", "副标题", "数码", new BigDecimal("99.00"),
                new BigDecimal("129.00"), 8, "新品", "{\"color\":\"black\"}"));
        assertThat(created.active()).isTrue();
        assertThat(service.overview(admin).products()).hasSize(1);

        var updated = service.updateProduct(admin, created.id(), new MerchantProductUpsertRequest(
                "改名商品", "新副标题", "数码", new BigDecimal("89.00"),
                new BigDecimal("129.00"), 12, "热卖", "{\"color\":\"white\"}"));
        assertThat(updated.name()).isEqualTo("改名商品");
        assertThat(updated.stock()).isEqualTo(12);

        assertThat(service.updateStatus(admin, created.id(),
                new MerchantProductStatusRequest(false)).active()).isFalse();
        assertThat(service.overview(admin).products().getFirst().active()).isFalse();
    }

    @Test
    void customerAndOtherStoreCannotMaintainMerchantData() {
        SessionAccount customer = new SessionAccount("customer", "platform", "buyer", "客户",
                AccountRole.CUSTOMER, null);
        assertThatThrownBy(() -> service.overview(customer)).isInstanceOf(SecurityException.class);

        SessionAccount otherAdmin = new SessionAccount("other", "merchant-two", "owner2", "其他店主",
                AccountRole.STORE_ADMIN, "STORE-TWO");
        assertThatThrownBy(() -> service.updateStore(otherAdmin,
                new MerchantStoreUpdateRequest("越权", "越", "越权修改", "北京")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productAttributesMustBeJsonObject() {
        assertThatThrownBy(() -> service.createProduct(admin, new MerchantProductUpsertRequest(
                "测试商品", "副标题", "数码", new BigDecimal("99.00"), null,
                8, null, "[1,2,3]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("商品属性必须是 JSON 对象");
    }
}
