package com.hanaki.ecom.security;

import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.AccountView;
import com.hanaki.ecom.domain.Domain.AuthResponse;
import com.hanaki.ecom.domain.Domain.CustomerRegisterRequest;
import com.hanaki.ecom.domain.Domain.LoginRequest;
import com.hanaki.ecom.domain.Domain.StaffRegisterRequest;
import com.hanaki.ecom.domain.Domain.TenantProvisionRequest;
import com.hanaki.ecom.domain.Domain.TenantView;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 应用自己的用户名/密码认证服务。
 * 密码使用 PBKDF2-HMAC-SHA256 加盐哈希；浏览器拿到的是随机会话令牌，数据库只保存令牌摘要。
 */
@Service
public class IdentityService {
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_]{3,32}");
    private static final int PASSWORD_ITERATIONS = 120_000;
    private final JdbcClient db;
    private final SecureRandom random = new SecureRandom();
    private final TenantService tenants;
    private final int sessionHours;
    private final int maxLoginAttempts;
    private final int loginLockMinutes;
    private final String dummyPasswordHash;

    public IdentityService(JdbcClient db, TenantService tenants,
                           @Value("${agent.security.session-hours:24}") int sessionHours,
                           @Value("${agent.security.max-login-attempts:5}") int maxLoginAttempts,
                           @Value("${agent.security.login-lock-minutes:15}") int loginLockMinutes) {
        this.db = db;
        this.tenants = tenants;
        this.sessionHours = sessionHours;
        this.maxLoginAttempts = Math.max(3, maxLoginAttempts);
        this.loginLockMinutes = Math.max(1, loginLockMinutes);
        // 不存在的用户名仍执行一次相同 PBKDF2，降低用户名枚举的时序差异。
        this.dummyPasswordHash = hashPassword(UUID.randomUUID().toString());
    }

    /**
     * 旧版 provisionTenant 把首个商家管理员写成 STORE_AGENT。只迁移与租户开通时间非常接近、
     * 且当前仍没有管理员的首个账号，避免把后来通过邀请码注册的普通客服意外提权。
     */
    @PostConstruct
    void migrateLegacyProvisionedAdmins() {
        record LegacyAdmin(String id, String tenantId, Instant accountCreatedAt, Instant tenantCreatedAt) {}
        Set<String> migratedTenants = new HashSet<>();
        db.sql("select a.id,a.tenant_id,a.created_at account_created_at,t.created_at tenant_created_at " +
                        "from app_account a join saas_tenant t on t.tenant_id=a.tenant_id " +
                        "where a.role='STORE_AGENT' and t.tenant_type='MERCHANT' and not exists(" +
                        "select 1 from app_account owner where owner.tenant_id=a.tenant_id " +
                        "and owner.role='STORE_ADMIN') order by a.tenant_id,a.created_at,a.id")
                .query((rs, row) -> new LegacyAdmin(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getTimestamp("account_created_at").toInstant(),
                        rs.getTimestamp("tenant_created_at").toInstant())).list()
                .forEach(candidate -> {
                    if (migratedTenants.contains(candidate.tenantId())) return;
                    Instant latest = candidate.tenantCreatedAt().plus(5, ChronoUnit.MINUTES);
                    if (candidate.accountCreatedAt().isBefore(candidate.tenantCreatedAt())
                            || candidate.accountCreatedAt().isAfter(latest)) return;
                    int updated = db.sql("update app_account set role='STORE_ADMIN' where id=:id " +
                                    "and tenant_id=:tenant and role='STORE_AGENT' and not exists(" +
                                    "select 1 from app_account owner where owner.tenant_id=:tenant " +
                                    "and owner.role='STORE_ADMIN')")
                            .param("id", candidate.id()).param("tenant", candidate.tenantId()).update();
                    if (updated == 1) migratedTenants.add(candidate.tenantId());
                });
    }

    @Transactional
    public AuthResponse registerCustomer(CustomerRegisterRequest request) {
        TenantView tenant = tenants.platform();
        Account account = createAccount(request.username(), request.password(), request.displayName(),
                AccountRole.CUSTOMER, null, tenant.tenantId());
        // 每位新客户获得 10,000 元可用额度。它是持久化账户余额，不是浏览器端演示数字。
        db.sql("insert into account_balance(account_id,tenant_id,available_balance,version,updated_at) " +
                        "values(:account,:tenant,10000,0,current_timestamp)")
                .param("account", account.id()).param("tenant", tenant.tenantId()).update();
        db.sql("insert into balance_ledger(id,account_id,tenant_id,entry_type,amount,balance_after,reference_id,description,created_at) " +
                        "values(:id,:account,:tenant,'INITIAL_GRANT',10000,10000,:reference,'新用户初始额度',current_timestamp)")
                .param("id", "BL-" + shortId()).param("account", account.id()).param("tenant", tenant.tenantId())
                .param("reference", account.id()).update();
        return issueSession(account);
    }

    @Transactional
    public AuthResponse registerStaff(StaffRegisterRequest request) {
        if (request.role() != AccountRole.STORE_AGENT && request.role() != AccountRole.OFFICIAL_AGENT) {
            throw new IllegalArgumentException("客服角色只能选择店铺客服或商城官方客服");
        }
        TenantView tenant = request.role() == AccountRole.OFFICIAL_AGENT
                ? tenants.platform() : tenants.requireMerchant(request.tenantCode());
        tenants.verifyStaffInvite(tenant.tenantId(), request.role(), request.inviteCode());
        String storeId = request.role() == AccountRole.STORE_AGENT ? tenant.storeId() : null;
        if (storeId != null) {
            int exists = db.sql("select count(*) from merchant_store where tenant_id=:tenant and id=:store")
                    .param("tenant", tenant.tenantId()).param("store", storeId).query(Integer.class).single();
            if (exists != 1) throw new IllegalArgumentException("店铺不存在，不能注册为该店铺客服");
        }
        return issueSession(createAccount(request.username(), request.password(), request.displayName(),
                request.role(), storeId, tenant.tenantId()));
    }

    /** 原子完成商家租户、默认店铺和首个店铺管理员的创建。 */
    @Transactional
    public AuthResponse provisionTenant(TenantProvisionRequest request) {
        TenantView tenant = tenants.provision(request);
        Account admin = createAccount(request.adminUsername(), request.adminPassword(),
                request.adminDisplayName(), AccountRole.STORE_ADMIN, tenant.storeId(), tenant.tenantId());
        return issueSession(admin);
    }

    @Transactional
    public AuthResponse loginCustomer(LoginRequest request) {
        return login(request, Set.of(AccountRole.CUSTOMER), TenantService.PLATFORM_TENANT_ID);
    }

    @Transactional
    public AuthResponse loginStaff(LoginRequest request) {
        String tenantId;
        try {
            tenantId = tenants.requireActive(request.tenantCode()).tenantId();
        } catch (IllegalArgumentException error) {
            verifyPassword(request.password(), dummyPasswordHash);
            throw new AuthenticationException("商家、用户名或密码错误");
        }
        return login(request, Set.of(AccountRole.STORE_ADMIN, AccountRole.STORE_AGENT,
                AccountRole.OFFICIAL_AGENT), tenantId);
    }

    public SessionAccount require(String authorization, Set<AccountRole> allowedRoles) {
        String raw = bearer(authorization);
        Account account = db.sql("select a.* from auth_session s join app_account a on a.id=s.account_id " +
                        "join saas_tenant t on t.tenant_id=a.tenant_id and t.status='ACTIVE' " +
                        "where s.token_hash=:hash and s.expires_at>current_timestamp and a.enabled=true")
                .param("hash", sha256(raw)).query(this::mapAccount).optional()
                .orElseThrow(() -> new AuthenticationException("登录已失效，请重新登录"));
        if (!allowedRoles.contains(account.role())) throw new SecurityException("当前账号无权访问此入口");
        return new SessionAccount(account.id(), account.tenantId(), account.username(), account.displayName(),
                account.role(), account.storeId());
    }

    public void logout(String authorization) {
        db.sql("delete from auth_session where token_hash=:hash").param("hash", sha256(bearer(authorization))).update();
    }

    private AuthResponse login(LoginRequest request, Set<AccountRole> roles, String tenantId) {
        String username = normalizeUsername(request.username());
        // 锁定账号行，使并发的失败计数、成功清零和锁定截止时间具有确定顺序。
        Account account = db.sql("select * from app_account where tenant_id=:tenant and username=:username and enabled=true for update")
                .param("tenant", tenantId).param("username", username).query(this::mapAccount).optional().orElse(null);
        if (account == null) {
            verifyPassword(request.password(), dummyPasswordHash);
            throw new AuthenticationException("用户名或密码错误");
        }
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(Instant.now()))
            throw new AuthenticationException("登录尝试过多，请稍后再试");
        if (!roles.contains(account.role()) || !verifyPassword(request.password(), account.passwordHash())) {
            recordLoginFailure(account);
            throw new AuthenticationException("用户名或密码错误");
        }
        db.sql("update app_account set failed_login_attempts=0,locked_until=null where id=:id and tenant_id=:tenant")
                .param("id", account.id()).param("tenant", account.tenantId()).update();
        return issueSession(account);
    }

    private void recordLoginFailure(Account account) {
        db.sql("update app_account set failed_login_attempts=failed_login_attempts+1 where id=:id and tenant_id=:tenant")
                .param("id", account.id()).param("tenant", account.tenantId()).update();
        int failures = db.sql("select failed_login_attempts from app_account where id=:id and tenant_id=:tenant")
                .param("id", account.id()).param("tenant", account.tenantId()).query(Integer.class).single();
        if (failures >= maxLoginAttempts) {
            db.sql("update app_account set locked_until=:until where id=:id and tenant_id=:tenant")
                    .param("until", Timestamp.from(Instant.now().plus(loginLockMinutes, ChronoUnit.MINUTES)))
                    .param("id", account.id()).param("tenant", account.tenantId()).update();
        }
    }

    private Account createAccount(String rawUsername, String password, String displayName,
                                  AccountRole role, String storeId, String tenantId) {
        String username = normalizeUsername(rawUsername);
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度必须为 8 至 128 位");
        }
        String name = requireText(displayName, "昵称不能为空");
        if (name.length() > 40) throw new IllegalArgumentException("昵称不能超过 40 个字符");
        Account account = new Account(role.name().substring(0, 3) + "-" + shortId(), tenantId, username,
                hashPassword(password), name, role, storeId, 0, null);
        try {
            db.sql("insert into app_account(id,tenant_id,username,password_hash,display_name,role,store_id,enabled,created_at) " +
                            "values(:id,:tenant,:username,:password,:name,:role,:store,true,current_timestamp)")
                    .param("id", account.id()).param("tenant", tenantId).param("username", username)
                    .param("password", account.passwordHash()).param("name", name).param("role", role.name())
                    .param("store", storeId).update();
        } catch (DataIntegrityViolationException error) {
            throw new IllegalArgumentException("该用户名已被注册");
        }
        return account;
    }

    private AuthResponse issueSession(Account account) {
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = Instant.now().plus(sessionHours, ChronoUnit.HOURS);
        db.sql("delete from auth_session where expires_at<=current_timestamp").update();
        db.sql("insert into auth_session(token_hash,account_id,expires_at,created_at) values(:hash,:account,:expires,current_timestamp)")
                .param("hash", sha256(token)).param("account", account.id())
                .param("expires", Timestamp.from(expiresAt)).update();
        return new AuthResponse(token, expiresAt, view(account));
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt);
        return PASSWORD_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String encoded) {
        if (password == null || encoded == null) return false;
        try {
            String[] values = encoded.split(":");
            byte[] salt = Base64.getDecoder().decode(values[1]);
            byte[] actual = pbkdf2(password, salt, Integer.parseInt(values[0]));
            return MessageDigest.isEqual(actual, Base64.getDecoder().decode(values[2]));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private byte[] pbkdf2(String password, byte[] salt) { return pbkdf2(password, salt, PASSWORD_ITERATIONS); }
    private byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("密码哈希初始化失败", error);
        }
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() < 20) {
            throw new AuthenticationException("请先登录");
        }
        return authorization.substring(7).strip();
    }

    private String normalizeUsername(String value) {
        String username = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) throw new IllegalArgumentException("用户名需为 3-32 位字母、数字或下划线");
        return username;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }

    private String shortId() { return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Account mapAccount(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Account(rs.getString("id"), rs.getString("tenant_id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("display_name"),
                AccountRole.valueOf(rs.getString("role")), rs.getString("store_id"),
                rs.getInt("failed_login_attempts"),
                rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant());
    }
    private AccountView view(Account account) {
        return new AccountView(account.id(), account.username(), account.displayName(), account.role(),
                account.tenantId(), account.storeId());
    }

    private record Account(String id, String tenantId, String username, String passwordHash,
                           String displayName, AccountRole role, String storeId,
                           int failedLoginAttempts, Instant lockedUntil) {}
    public record SessionAccount(String id, String tenantId, String username, String displayName,
                                 AccountRole role, String storeId) {}
    public static final class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) { super(message); }
    }
}
