package com.hanaki.ecom.security;

import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.TenantProvisionRequest;
import com.hanaki.ecom.domain.Domain.TenantView;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 商家 SaaS 租户目录；平台用户和平台官方账号不属于任何商家租户。 */
@Service
public class TenantService {
    public static final String PLATFORM_TENANT_ID = "platform";
    private static final Pattern TENANT_CODE = Pattern.compile("[a-z][a-z0-9-]{2,31}");
    private final JdbcClient db;
    private final String platformName;
    private final String defaultStoreInviteCode;
    private final String officialInviteCode;
    private final String provisioningKey;

    public TenantService(JdbcClient db,
                         @Value("${agent.security.platform-name:花木商城平台}") String platformName,
                         @Value("${agent.security.store-agent-invite-code}") String defaultStoreInviteCode,
                         @Value("${agent.security.official-agent-invite-code}") String officialInviteCode,
                         @Value("${agent.security.tenant-provisioning-key}") String provisioningKey) {
        this.db = db;
        this.platformName = requireText(platformName, "平台名称不能为空");
        this.defaultStoreInviteCode = requireText(defaultStoreInviteCode, "默认商家邀请码不能为空");
        this.officialInviteCode = requireText(officialInviteCode, "平台官方邀请码不能为空");
        this.provisioningKey = requireText(provisioningKey, "租户开通密钥不能为空");
    }

    @PostConstruct
    public void initializeTenantDirectory() {
        insertTenant(PLATFORM_TENANT_ID, platformName, "PLATFORM", null,
                defaultStoreInviteCode, officialInviteCode, true);
        // 演示商品数据先由 data.sql 写入；这里把每一个店铺登记为独立商家租户。
        db.sql("select tenant_id,id,name from merchant_store order by id")
                .query((rs, row) -> new MerchantSeed(rs.getString("tenant_id"), rs.getString("id"),
                        rs.getString("name"))).list()
                .forEach(store -> insertTenant(store.tenantId(), store.name(), "MERCHANT", store.storeId(),
                        defaultStoreInviteCode, officialInviteCode, true));
    }

    /** 登录页公开的只是已启用商家名称和代码，不包含平台作用域与任何秘密。 */
    public List<TenantView> activeTenants() {
        return db.sql("select tenant_id,tenant_code,display_name,status,tenant_type,primary_store_id " +
                        "from saas_tenant where status='ACTIVE' and tenant_type='MERCHANT' " +
                        "order by created_at,tenant_code")
                .query((rs, row) -> mapTenant(rs)).list();
    }

    public TenantView platform() { return requireTenant(PLATFORM_TENANT_ID, "PLATFORM"); }

    public TenantView requireMerchant(String rawTenantCode) {
        return requireTenant(normalizeCode(rawTenantCode), "MERCHANT");
    }

    public TenantView requireActive(String rawTenantCode) {
        String code = normalizeCode(rawTenantCode);
        return requireTenant(code, null);
    }

    /** 平台开通一个商家租户，并创建该商家的默认店铺；首个商家管理员由 IdentityService 创建。 */
    public TenantView provision(TenantProvisionRequest request) {
        if (!constantTimeEquals(provisioningKey, request.provisioningKey())) {
            throw new SecurityException("商家租户开通密钥不正确");
        }
        String code = normalizeCode(request.tenantCode());
        if (PLATFORM_TENANT_ID.equals(code)) throw new IllegalArgumentException("platform 是平台保留代码");
        String name = requireText(request.tenantName(), "商家名称不能为空");
        if (name.length() > 120) throw new IllegalArgumentException("商家名称不能超过 120 个字符");
        String storeId = "STORE-" + code.toUpperCase(Locale.ROOT);
        insertTenant(code, name, "MERCHANT", storeId, request.storeAgentInviteCode(),
                officialInviteCode, false);
        db.sql("insert into merchant_store(id,tenant_id,name,logo_text,description,service_score," +
                        "fulfillment_score,location,created_at) values(:store,:tenant,:name,:logo," +
                        "'新开通商家，待完善店铺介绍',5,5,'待配置',current_timestamp)")
                .param("store", storeId).param("tenant", code).param("name", name)
                .param("logo", name.substring(0, 1)).update();
        return requireMerchant(code);
    }

    public void verifyStaffInvite(String tenantId, AccountRole role, String candidate) {
        String column = role == AccountRole.STORE_AGENT
                ? "store_agent_invite_hash" : "official_agent_invite_hash";
        String expected = db.sql("select " + column + " from saas_tenant " +
                        "where tenant_id=:tenant and status='ACTIVE'")
                .param("tenant", tenantId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("商家租户不存在或已停用"));
        if (candidate == null || !constantTimeEquals(expected, sha256(candidate))) {
            throw new SecurityException("客服注册邀请码不正确");
        }
    }

    private TenantView requireTenant(String code, String requiredType) {
        String sql = "select tenant_id,tenant_code,display_name,status,tenant_type,primary_store_id " +
                "from saas_tenant where tenant_code=:code and status='ACTIVE'" +
                (requiredType == null ? "" : " and tenant_type=:type");
        var query = db.sql(sql).param("code", code);
        if (requiredType != null) query = query.param("type", requiredType);
        return query.query((rs, row) -> mapTenant(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("商家租户不存在或已停用"));
    }

    private TenantView mapTenant(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TenantView(rs.getString("tenant_id"), rs.getString("tenant_code"),
                rs.getString("display_name"), rs.getString("status"), rs.getString("tenant_type"),
                rs.getString("primary_store_id"));
    }

    private void insertTenant(String code, String name, String type, String storeId,
                              String storeInvite, String officialInvite, boolean ignoreExisting) {
        String storeHash = sha256(requireText(storeInvite, "商家客服邀请码不能为空"));
        String officialHash = sha256(requireText(officialInvite, "官方客服邀请码不能为空"));
        try {
            db.sql("insert into saas_tenant(tenant_id,tenant_code,display_name,status,tenant_type," +
                            "primary_store_id,store_agent_invite_hash,official_agent_invite_hash,created_at,updated_at) " +
                            "values(:id,:code,:name,'ACTIVE',:type,:store,:storeHash,:officialHash," +
                            "current_timestamp,current_timestamp)")
                    .param("id", code).param("code", code).param("name", name).param("type", type)
                    .param("store", storeId).param("storeHash", storeHash)
                    .param("officialHash", officialHash).update();
        } catch (DataIntegrityViolationException error) {
            if (!ignoreExisting) throw new IllegalArgumentException("商家租户代码已被使用");
        }
        if ("PLATFORM".equals(type)) {
            db.sql("insert into platform_balance(tenant_id,available_balance,version,updated_at) " +
                            "values(:tenant,0,0,current_timestamp) on conflict do nothing")
                    .param("tenant", PLATFORM_TENANT_ID).update();
        }
    }

    private String normalizeCode(String value) {
        String code = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!TENANT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("商家代码需为 3-32 位小写字母、数字或连字符，且以字母开头");
        }
        return code;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("邀请码摘要初始化失败", error); }
    }

    private record MerchantSeed(String tenantId, String storeId, String name) {}
}
