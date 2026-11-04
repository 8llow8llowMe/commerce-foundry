package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 동결한 구 product/sku 확장과 관련 네 매핑만 검증한다. 전체 서비스 구 스키마 이행 테스트는 아니다. */
@Testcontainers
@EnabledWithDockerOrOnCi
class PriceBookSchemaUpgradePostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void extendsFrozenOldTablesWithoutChangingAnyLegacyColumnOrDataAndValidatesFourMappings() throws Exception {
        try (Connection connection = connection()) {
            createOldSchema(connection);
            seedLegacy(connection);
            var products = rows(connection, "select * from product.product order by id");
            var skus = rows(connection, "select * from product.sku order by id");
            var shape = legacyShape(connection);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("ddl/product-price-extension.sql"));
            assertThat(rows(connection, "select * from product.product order by id")).isEqualTo(products);
            assertThat(rows(connection, "select * from product.sku order by id")).isEqualTo(skus);
            assertThat(legacyShape(connection)).isEqualTo(shape);
            assertThat(rows(connection, "select count(*) from product.product_price")).containsExactly(List.of("0"));
            assertThat(rows(connection, "select count(*) from product.sku_price")).containsExactly(List.of("0"));
            validateFourMappings();
        }
    }

    @Test
    void extensionAndNewRowsRollbackTogetherWhileLegacySchemaAndValuesSurvive() throws Exception {
        try (Connection connection = connection()) {
            createOldSchema(connection);
            seedLegacy(connection);
            var before = rows(connection, "select * from product.product order by id");
            var shape = legacyShape(connection);
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("ddl/product-price-extension.sql"));
            connection.createStatement().executeUpdate("insert into product.product_price "
                + "(id,product_id,sale_price_amount,sale_price_currency,created_at,updated_at) values (1,1,0,'KRW',current_timestamp,current_timestamp)");
            connection.createStatement().executeUpdate("insert into product.sku_price "
                + "(id,sku_id,additional_price_amount,additional_price_currency,created_at,updated_at) values (1,1,0,'KRW',current_timestamp,current_timestamp)");
            connection.rollback();
            connection.setAutoCommit(true);
            assertThat(rows(connection, "select to_regclass('product.product_price') is null, to_regclass('product.sku_price') is null"))
                .containsExactly(List.of("t", "t"));
            assertThat(rows(connection, "select * from product.product order by id")).isEqualTo(before);
            assertThat(legacyShape(connection)).isEqualTo(shape);
        }
    }

    private static void createOldSchema(Connection connection) throws SQLException {
        connection.createStatement().execute("drop schema if exists product cascade");
        connection.createStatement().execute("create schema product");
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("ddl/product-sku-before-price.sql"));
    }

    private static void seedLegacy(Connection connection) throws SQLException {
        for (int id = 1; id <= 2; id++) {
            String currency = id == 1 ? "KRW" : "USD";
            String list = id == 1 ? "null,null" : "200,'USD'";
            String deletedAt = id == 1 ? "null" : "'2022-03-04T00:00:00Z'";
            String status = id == 1 ? "'ON_SALE'" : "'STOP_SELLING'";
            connection.createStatement().executeUpdate("insert into product.product "
                + "(id,created_at,updated_at,category_id,created_by_member_id,custom_order,custom_order_lead_time_days,custom_order_notice,"
                + "deleted_at,description_html,description_html_en,expected_ship_date,list_price_amount,list_price_currency,max_quantity_per_order,"
                + "name,name_en,preorder,sale_price_amount,sale_price_currency,sale_status,summary,summary_en,version,visible) values ("
                + id + ",'2020-01-01T00:00:00Z','2021-02-03T00:00:00Z',99,77,true,14,'notice'," + deletedAt
                + ",'<p>설명</p>','<p>English</p>','2027-01-01'," + list + ",3,'상품','name',true," + (id == 1 ? 0 : 100)
                + ",'" + currency + "'," + status + ",'요약','summary',7," + (id == 1) + ")");
            connection.createStatement().executeUpdate("insert into product.sku "
                + "(id,created_at,updated_at,additional_price_amount,additional_price_currency,hs_code,option_value_key,product_id,sale_status,weight_grams) values ("
                + id + ",'2020-01-01T00:00:00Z','2021-02-03T00:00:00Z',0,'" + currency + "','621142',''," + id + ",'STOP_SELLING',350)");
        }
    }

    private static List<List<String>> legacyShape(Connection connection) throws SQLException {
        List<List<String>> shape = new ArrayList<>();
        shape.addAll(rows(connection, "select * from information_schema.columns "
            + "where table_schema='product' and table_name in ('product','sku') order by table_name,ordinal_position"));
        shape.addAll(rows(connection, "select conrelid::regclass::text,conname,pg_get_constraintdef(oid) from pg_constraint "
            + "where conrelid in ('product.product'::regclass,'product.sku'::regclass) order by conname"));
        shape.addAll(rows(connection, "select tablename,indexname,indexdef from pg_indexes where schemaname='product' "
            + "and tablename in ('product','sku') order by indexname"));
        shape.addAll(rows(connection, "select c.relname,d.objsubid::text,d.description from pg_description d join pg_class c on c.oid=d.objoid "
            + "join pg_namespace n on n.oid=c.relnamespace where n.nspname='product' and c.relname in ('product','sku') order by c.relname,d.objsubid"));
        return shape;
    }

    private static List<List<String>> rows(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            List<List<String>> rows = new ArrayList<>();
            while (result.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    values.add(result.getString(i));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void validateFourMappings() {
        var registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", POSTGRES.getJdbcUrl())
            .applySetting("hibernate.connection.username", POSTGRES.getUsername())
            .applySetting("hibernate.connection.password", POSTGRES.getPassword())
            .applySetting("hibernate.default_schema", "product")
            .applySetting("hibernate.hbm2ddl.auto", "validate")
            .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
            .applySetting("hibernate.implicit_naming_strategy", "org.springframework.boot.hibernate.SpringImplicitNamingStrategy").build();
        try {
            new MetadataSources(registry).addAnnotatedClass(ProductEntity.class).addAnnotatedClass(SkuEntity.class)
                .addAnnotatedClass(ProductPriceEntity.class).addAnnotatedClass(SkuPriceEntity.class).buildMetadata().buildSessionFactory().close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
