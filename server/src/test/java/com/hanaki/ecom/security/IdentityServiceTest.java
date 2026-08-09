package com.hanaki.ecom.security;

import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.CustomerRegisterRequest;
import com.hanaki.ecom.domain.Domain.LoginRequest;
import com.hanaki.ecom.domain.Domain.StaffRegisterRequest;
import com.hanaki.ecom.domain.Domain.TenantProvisionRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityServiceTest {
    private JdbcClient db;
    private IdentityService identity;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:identity-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), dataSource);
        db = JdbcClient.create(dataSource);
        TenantService tenants = new TenantService(db, "测试平台", "store-code",
                "official-code", "provisioning-key");
        tenants.initializeTenantDirectory();
        identity = new IdentityService(db, tenants, 24, 3, 15);
    }

    @Test
    void repeatedFailuresLockAccountAndSuccessfulLoginAfterExpiryResetsCounter() {
        identity.registerCustomer(new CustomerRegisterRequest("alice_01", "correct-pass", "Alice"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> identity.loginCustomer(new LoginRequest(null, "alice_01", "wrong-pass")))
                    .isInstanceOf(IdentityService.AuthenticationException.class)
                    .hasMessage("用户名或密码错误");
        }
        assertThatThrownBy(() -> identity.loginCustomer(new LoginRequest(null, "alice_01", "correct-pass")))
                .isInstanceOf(IdentityService.AuthenticationException.class)
                .hasMessage("登录尝试过多，请稍后再试");

        db.sql("update app_account set locked_until=dateadd('MINUTE',-1,current_timestamp) where username='alice_01'")
                .update();
        assertThat(identity.loginCustomer(new LoginRequest(null, "alice_01", "correct-pass")).token()).isNotBlank();
        assertThat(db.sql("select failed_login_attempts from app_account where username='alice_01'")
                .query(Integer.class).single()).isZero();
        assertThat(db.sql("select count(*) from app_account where username='alice_01' and locked_until is null")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void storeAgentIsBoundToMerchantDefaultStoreAndCannotForgeStoreId() {
        identity.provisionTenant(new TenantProvisionRequest("second-mall", "第二商家",
                "second_admin", "correct-pass", "二号管理员", "second-store-code", "provisioning-key"));
        StaffRegisterRequest request = new StaffRegisterRequest("second-mall", "store_agent", "correct-pass", "客服",
                AccountRole.STORE_AGENT, "MISSING", "second-store-code");
        var registered = identity.registerStaff(request);
        assertThat(registered.account().storeId()).isEqualTo("STORE-SECOND-MALL");
        assertThat(registered.account().tenantId()).isEqualTo("second-mall");
    }

    @Test
    void provisioningCreatesMerchantAdminWithoutCreatingPerMerchantPlatformWallet() {
        var response = identity.provisionTenant(new TenantProvisionRequest("second-mall", "第二商家",
                "second_admin", "correct-pass", "二号管理员", "second-store-code", "provisioning-key"));

        assertThat(response.account().tenantId()).isEqualTo("second-mall");
        assertThat(response.account().role()).isEqualTo(AccountRole.STORE_ADMIN);
        assertThat(response.account().storeId()).isEqualTo("STORE-SECOND-MALL");
        assertThat(identity.loginStaff(new LoginRequest("second-mall", "second_admin", "correct-pass"))
                .account().role()).isEqualTo(AccountRole.STORE_ADMIN);
        assertThat(db.sql("select count(*) from platform_balance where tenant_id='second-mall'")
                .query(Integer.class).single()).isZero();
        assertThat(db.sql("select available_balance from platform_balance where tenant_id='platform'")
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0");
    }

    @Test
    void staffRegistrationCannotCreateAnotherMerchantAdmin() {
        identity.provisionTenant(new TenantProvisionRequest("second-mall", "第二商家",
                "second_admin", "correct-pass", "二号管理员", "second-store-code", "provisioning-key"));
        assertThatThrownBy(() -> identity.registerStaff(new StaffRegisterRequest("second-mall", "forged_admin",
                "correct-pass", "伪造管理员", AccountRole.STORE_ADMIN, null, "second-store-code")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("客服角色只能选择店铺客服或商城官方客服");
    }

    @Test
    void legacyProvisionedFirstAgentIsMigratedToMerchantAdmin() {
        var response = identity.provisionTenant(new TenantProvisionRequest("legacy-mall", "旧版商家",
                "legacy_admin", "correct-pass", "旧版管理员", "legacy-store-code", "provisioning-key"));
        db.sql("update app_account set role='STORE_AGENT' where id=:id")
                .param("id", response.account().id()).update();

        identity.migrateLegacyProvisionedAdmins();

        assertThat(identity.loginStaff(new LoginRequest("legacy-mall", "legacy_admin", "correct-pass"))
                .account().role()).isEqualTo(AccountRole.STORE_ADMIN);
    }

    @Test
    void customerIdentityIsPlatformGlobalWhileMerchantStaffRemainTenantScoped() {
        identity.provisionTenant(new TenantProvisionRequest("second-mall", "第二商家",
                "second_admin", "correct-pass", "二号管理员", "second-store-code", "provisioning-key"));
        identity.provisionTenant(new TenantProvisionRequest("third-mall", "第三商家",
                "third_admin", "correct-pass", "三号管理员", "third-store-code", "provisioning-key"));
        identity.registerCustomer(new CustomerRegisterRequest("shared_user", "customer-pass", "平台用户"));
        identity.registerStaff(new StaffRegisterRequest("second-mall", "same_staff", "second-pass", "二号客服",
                AccountRole.STORE_AGENT, null, "second-store-code"));
        identity.registerStaff(new StaffRegisterRequest("third-mall", "same_staff", "third-pass", "三号客服",
                AccountRole.STORE_AGENT, null, "third-store-code"));

        assertThat(identity.loginCustomer(new LoginRequest("third-mall", "shared_user", "customer-pass"))
                .account().tenantId()).isEqualTo("platform");
        assertThat(identity.loginStaff(new LoginRequest("second-mall", "same_staff", "second-pass"))
                .account().storeId()).isEqualTo("STORE-SECOND-MALL");
        assertThat(identity.loginStaff(new LoginRequest("third-mall", "same_staff", "third-pass"))
                .account().storeId()).isEqualTo("STORE-THIRD-MALL");
    }
}
