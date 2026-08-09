package com.hanaki.ecom.store;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class EcommerceStoreOrderScopeTest {
    private JdbcClient db;
    private EcommerceStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:order-scope-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("schema.sql")), source);
        db = JdbcClient.create(source);
        db.sql("insert into merchant_store(id,tenant_id,name,created_at) values" +
                "('STORE-1','tenant-a','店铺一',current_timestamp)," +
                "('STORE-2','tenant-a','店铺二',current_timestamp)," +
                "('STORE-B','tenant-b','其他商家',current_timestamp)").update();
        db.sql("insert into product(id,tenant_id,store_id,name,stock) values" +
                "('P1','tenant-a','STORE-1','同店其他商品',10)," +
                "('P2','tenant-a','STORE-1','当前商品',10)," +
                "('P3','tenant-a','STORE-2','其他店商品',10)," +
                "('P4','tenant-b','STORE-B','其他商家商品',10)").update();
        db.sql("insert into customer_order(id,tenant_id,user_id,product_id,amount,status,payment_status,logistics_status,created_at) values" +
                "('OD-1','tenant-a','user-a','P1',10,'PAID','BALANCE_PAID','待发货',dateadd('MINUTE',-3,current_timestamp))," +
                "('OD-2','tenant-a','user-a','P2',20,'PAID','BALANCE_PAID','待发货',dateadd('MINUTE',-2,current_timestamp))," +
                "('OD-3','tenant-a','user-a','P3',30,'PAID','BALANCE_PAID','待发货',dateadd('MINUTE',-1,current_timestamp))," +
                "('OD-4','tenant-a','user-b','P2',40,'PAID','BALANCE_PAID','待发货',current_timestamp)," +
                "('OD-B','tenant-b','user-a','P4',50,'PAID','BALANCE_PAID','待发货',current_timestamp)").update();
        store = new EcommerceStore(db);
    }

    @Test
    void productScopeDoesNotReturnOtherProductsFromSameStore() {
        assertThat(store.recentOrdersForProduct("tenant-a", "user-a", "STORE-1", "P2"))
                .extracting(order -> order.id()).containsExactly("OD-2");
    }

    @Test
    void storeScopeReturnsOnlyCurrentUsersOrdersFromThatStore() {
        assertThat(store.recentOrdersForStore("tenant-a", "user-a", "STORE-1"))
                .extracting(order -> order.id()).containsExactly("OD-2", "OD-1");
    }

    @Test
    void merchantScopeCannotQueryAnotherMerchantsProduct() {
        assertThat(store.recentOrdersForProduct("tenant-a", "user-a", "STORE-B", "P4")).isEmpty();
    }

    @Test
    void exactOwnedOrderLookupIsNotLimitedToTenMostRecentOrders() {
        for (int index = 0; index < 11; index++) {
            db.sql("insert into customer_order(id,tenant_id,user_id,product_id,amount,status,payment_status," +
                            "logistics_status,created_at) values(:id,'tenant-a','user-a','P1',10,'PAID'," +
                            "'BALANCE_PAID','待发货',dateadd('MINUTE',:offset,current_timestamp))")
                    .param("id", "OD-NEW-" + index).param("offset", index + 1).update();
        }

        assertThat(store.recentOrders("platform", "user-a"))
                .extracting(order -> order.id()).doesNotContain("OD-1");
        assertThat(store.ownedOrder("platform", "user-a", "OD-1"))
                .hasValueSatisfying(order -> assertThat(order.tenantId()).isEqualTo("tenant-a"));
        assertThat(store.ownedOrder("tenant-b", "user-a", "OD-1")).isEmpty();
    }
}
