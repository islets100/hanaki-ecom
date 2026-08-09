package com.hanaki.ecom.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanaki.ecom.domain.Domain.AccountRole;
import com.hanaki.ecom.domain.Domain.MerchantOverview;
import com.hanaki.ecom.domain.Domain.MerchantProductStatusRequest;
import com.hanaki.ecom.domain.Domain.MerchantProductUpsertRequest;
import com.hanaki.ecom.domain.Domain.MerchantProductView;
import com.hanaki.ecom.domain.Domain.MerchantStore;
import com.hanaki.ecom.domain.Domain.MerchantStoreUpdateRequest;
import com.hanaki.ecom.security.IdentityService.SessionAccount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 商家管理员专用的店铺与商品维护服务，所有数据范围都从登录会话中取得。 */
@Service
public class MerchantManagementService {
    private final JdbcClient db;
    private final ObjectMapper json;

    public MerchantManagementService(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public MerchantOverview overview(SessionAccount admin) {
        requireAdmin(admin);
        return new MerchantOverview(store(admin), products(admin));
    }

    @Transactional
    public MerchantStore updateStore(SessionAccount admin, MerchantStoreUpdateRequest request) {
        requireAdmin(admin);
        int updated = db.sql("update merchant_store set name=:name,logo_text=:logo,description=:description," +
                        "location=:location where tenant_id=:tenant and id=:store")
                .param("name", text(request.name())).param("logo", text(request.logoText()))
                .param("description", text(request.description())).param("location", text(request.location()))
                .param("tenant", admin.tenantId()).param("store", admin.storeId()).update();
        if (updated != 1) throw new IllegalArgumentException("店铺不存在或不属于当前商家");
        return store(admin);
    }

    @Transactional
    public MerchantProductView createProduct(SessionAccount admin, MerchantProductUpsertRequest request) {
        requireAdmin(admin);
        ProductValues values = values(request);
        String id = "P-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        db.sql("insert into product(id,tenant_id,store_id,name,subtitle,category,price,old_price,stock,badge," +
                        "attributes_json,active) values(:id,:tenant,:store,:name,:subtitle,:category,:price," +
                        ":oldPrice,:stock,:badge,:attributes,true)")
                .param("id", id).param("tenant", admin.tenantId()).param("store", admin.storeId())
                .param("name", values.name()).param("subtitle", values.subtitle())
                .param("category", values.category()).param("price", values.price())
                .param("oldPrice", values.oldPrice()).param("stock", values.stock())
                .param("badge", values.badge()).param("attributes", values.attributesJson()).update();
        return product(admin, id);
    }

    @Transactional
    public MerchantProductView updateProduct(SessionAccount admin, String productId,
                                             MerchantProductUpsertRequest request) {
        requireAdmin(admin);
        ProductValues values = values(request);
        int updated = db.sql("update product set name=:name,subtitle=:subtitle,category=:category,price=:price," +
                        "old_price=:oldPrice,stock=:stock,badge=:badge,attributes_json=:attributes " +
                        "where id=:id and tenant_id=:tenant and store_id=:store")
                .param("name", values.name()).param("subtitle", values.subtitle())
                .param("category", values.category()).param("price", values.price())
                .param("oldPrice", values.oldPrice()).param("stock", values.stock())
                .param("badge", values.badge()).param("attributes", values.attributesJson())
                .param("id", productId).param("tenant", admin.tenantId()).param("store", admin.storeId()).update();
        if (updated != 1) throw new IllegalArgumentException("商品不存在或不属于当前店铺");
        return product(admin, productId);
    }

    @Transactional
    public MerchantProductView updateStatus(SessionAccount admin, String productId,
                                            MerchantProductStatusRequest request) {
        requireAdmin(admin);
        int updated = db.sql("update product set active=:active where id=:id and tenant_id=:tenant and store_id=:store")
                .param("active", request.active()).param("id", productId)
                .param("tenant", admin.tenantId()).param("store", admin.storeId()).update();
        if (updated != 1) throw new IllegalArgumentException("商品不存在或不属于当前店铺");
        return product(admin, productId);
    }

    private MerchantStore store(SessionAccount admin) {
        return db.sql("select * from merchant_store where tenant_id=:tenant and id=:store")
                .param("tenant", admin.tenantId()).param("store", admin.storeId())
                .query((rs, row) -> new MerchantStore(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("name"), rs.getString("logo_text"), rs.getString("description"),
                        rs.getBigDecimal("service_score"), rs.getBigDecimal("fulfillment_score"),
                        rs.getString("location"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("店铺不存在或不属于当前商家"));
    }

    private List<MerchantProductView> products(SessionAccount admin) {
        return db.sql("select * from product where tenant_id=:tenant and store_id=:store order by id")
                .param("tenant", admin.tenantId()).param("store", admin.storeId())
                .query(this::mapProduct).list();
    }

    private MerchantProductView product(SessionAccount admin, String productId) {
        return db.sql("select * from product where id=:id and tenant_id=:tenant and store_id=:store")
                .param("id", productId).param("tenant", admin.tenantId()).param("store", admin.storeId())
                .query(this::mapProduct).optional()
                .orElseThrow(() -> new IllegalArgumentException("商品不存在或不属于当前店铺"));
    }

    private MerchantProductView mapProduct(ResultSet rs, int row) throws SQLException {
        return new MerchantProductView(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("store_id"), rs.getString("name"), rs.getString("subtitle"),
                rs.getString("category"), rs.getBigDecimal("price"), rs.getBigDecimal("old_price"),
                rs.getInt("stock"), rs.getString("badge"), rs.getString("attributes_json"),
                rs.getBoolean("active"));
    }

    private ProductValues values(MerchantProductUpsertRequest request) {
        if (request.oldPrice() != null && request.oldPrice().compareTo(request.price()) < 0)
            throw new IllegalArgumentException("划线价不能低于销售价");
        return new ProductValues(text(request.name()), nullable(request.subtitle()), text(request.category()),
                request.price(), request.oldPrice(), request.stock(), nullable(request.badge()),
                attributes(request.attributesJson()));
    }

    private String attributes(String source) {
        try {
            JsonNode node = json.readTree(source);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("商品属性必须是 JSON 对象");
            return json.writeValueAsString(node);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("商品属性不是有效的 JSON 对象");
        }
    }

    private void requireAdmin(SessionAccount account) {
        if (account == null || account.role() != AccountRole.STORE_ADMIN
                || account.storeId() == null || account.storeId().isBlank())
            throw new SecurityException("只有商家管理员可以维护店铺和商品");
    }

    private String text(String value) { return value == null ? "" : value.strip(); }
    private String nullable(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    private record ProductValues(String name, String subtitle, String category, BigDecimal price,
                                 BigDecimal oldPrice, int stock, String badge, String attributesJson) {}
}
