package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.PriceMigrationReadAdapter;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuRow;
import com.commercefoundry.domainlayer.product.application.port.out.PriceMigrationReadPort;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Finding;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Verdict;
import com.commercefoundry.domainlayer.product.application.port.in.PriceMigrationPreflightUseCase;
import com.commercefoundry.domainlayer.product.application.service.PriceMigrationPreflightFacade;
import com.commercefoundry.domainlayer.product.application.service.processor.PriceMigrationPreflightProcessor;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 테스트 전용 H2 DB와 전용 PostgreSQL 컨테이너에서 같은 원천 조회 계약을 검증한다. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.commercefoundry.PriceMigrationReadIntegrationTest$SqlCounter",
    "spring.datasource.url=jdbc:h2:mem:price-migration-read;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductServiceFeaturesConfig.class, QuerydslConfigurer.class, PriceMigrationReadAdapter.class,
    PriceMigrationPreflightFacade.class, PriceMigrationPreflightProcessor.class})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PriceMigrationReadIntegrationTest {
    /** SQL 문자열은 저장하지 않고 현재 스레드의 SELECT/DML 개수만 센다. */
    public static class SqlCounter implements StatementInspector {
        static final ThreadLocal<int[]> COUNTS = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            int[] counts = COUNTS.get();
            if (counts != null) {
                String verb = sql.stripLeading().toLowerCase(Locale.ROOT);
                if (verb.startsWith("select")) counts[0]++;
                if (verb.startsWith("insert") || verb.startsWith("update") || verb.startsWith("delete") || verb.startsWith("merge")) counts[1]++;
            }
            return sql;
        }
    }

    @Autowired PriceMigrationReadPort port;
    @Autowired PriceMigrationPreflightUseCase preflight;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory emf;
    JdbcTemplate jdbc;
    Statistics statistics;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        boolean h2 = isH2();
        var protectedBefore = protectedStructure();
        // 오직 테스트 전용 DB에서 손상 원천을 주입한다. 운영 DDL·엔티티는 바꾸지 않는다.
        // unrelated CHECK·SKU 옵션 UNIQUE·PK·NOT NULL은 보존한다.
        for (Lane lane : Lane.values()) {
            String table = table(lane);
            // PostgreSQL information_schema는 NOT NULL도 합성 CHECK로 노출한다.
            // 실제 DROP 가능한 pg_constraint의 CHECK만 선택해 NOT NULL을 완화 대상으로 오인하지 않는다.
            var checks = h2
                ? jdbc.queryForList("select tc.constraint_name, cc.check_clause from information_schema.table_constraints tc "
                    + "join information_schema.check_constraints cc on tc.constraint_schema=cc.constraint_schema and tc.constraint_name=cc.constraint_name "
                    + "where tc.table_schema='product' and tc.table_name=? and tc.constraint_type='CHECK'", table)
                : jdbc.queryForList("select c.conname as constraint_name, pg_catalog.pg_get_constraintdef(c.oid) as check_clause "
                    + "from pg_catalog.pg_constraint c join pg_catalog.pg_class t on t.oid=c.conrelid "
                    + "join pg_catalog.pg_namespace n on n.oid=t.relnamespace "
                    + "where n.nspname='product' and t.relname=? and c.contype='c'", table);
            for (Map<String, Object> check : checks) {
                String clause = check.get("check_clause").toString().toLowerCase(Locale.ROOT);
                if (clause.contains("price_") || clause.contains("sale_status") && !clause.contains("deleted_at")
                    || check.get("constraint_name").toString().toLowerCase(Locale.ROOT).endsWith("_ids_positive")) {
                    drop(table, check.get("constraint_name").toString());
                }
            }
            if (lane == Lane.PRODUCT_PRICE || lane == Lane.SKU_PRICE) {
                var names = jdbc.queryForList("select constraint_name from information_schema.table_constraints "
                    + "where table_schema='product' and table_name=? and constraint_type='UNIQUE'", String.class, table);
                for (String name : names) drop(table, name);
            }
        }
        if (h2) {
            // H2는 enum을 자체 타입으로 생성한다. 손상 관측 fixture에서만 문자열 컬럼으로 완화한다.
            for (Lane lane : Lane.values()) {
                String amountCurrency = lane == Lane.PRODUCT || lane == Lane.PRODUCT_PRICE ? "sale_price_currency" : "additional_price_currency";
                jdbc.execute("alter table product." + table(lane) + " alter column " + amountCurrency + " varchar(3)");
                if (lane == Lane.PRODUCT || lane == Lane.PRODUCT_PRICE) {
                    jdbc.execute("alter table product." + table(lane) + " alter column list_price_currency varchar(3)");
                }
                if (lane == Lane.PRODUCT || lane == Lane.SKU) {
                    jdbc.execute("alter table product." + table(lane) + " alter column sale_status varchar(20)");
                }
            }
        }
        assertThat(protectedStructure()).as("손상 fixture 준비가 NOT NULL·PK·SKU 옵션 UNIQUE를 보존한다").isEqualTo(protectedBefore);
    }

    private boolean isH2() {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().equals("H2");
        } catch (java.sql.SQLException failure) {
            throw new AssertionError("테스트 DB 메타데이터 확인 실패", failure);
        }
    }

    private List<List<Map<String, Object>>> protectedStructure() {
        var columns = jdbc.queryForList("select table_name,column_name,is_nullable from information_schema.columns "
            + "where table_schema='product' and table_name in ('product','sku','product_price','sku_price') order by table_name,ordinal_position");
        assertThat(columns).filteredOn(column -> "id".equals(column.get("column_name")))
            .hasSize(4).allSatisfy(column -> assertThat(column.get("is_nullable")).isEqualTo("NO"));
        var constraints = jdbc.queryForList("select table_name,constraint_name,constraint_type from information_schema.table_constraints "
            + "where table_schema='product' and table_name in ('product','sku','product_price','sku_price') "
            + "and (constraint_type='PRIMARY KEY' or (table_name='sku' and constraint_type='UNIQUE')) order by table_name,constraint_name");
        assertThat(constraints).filteredOn(constraint -> "PRIMARY KEY".equals(constraint.get("constraint_type"))).hasSize(4);
        assertThat(constraints).filteredOn(constraint -> "UNIQUE".equals(constraint.get("constraint_type")))
            .singleElement().satisfies(constraint -> assertThat(constraint.get("constraint_name")).isEqualTo("uk_sku_product_id_option_value_key"));
        return List.of(columns, constraints);
    }

    private void drop(String table, String name) {
        // 두 값은 테스트 DB 메타데이터·고정 enum에서만 오며 식별자는 이중 따옴표로 escape한다.
        jdbc.execute("alter table product." + table + " drop constraint \"" + name.replace("\"", "\"\"") + "\"");
    }

    @AfterEach
    void clean() {
        SqlCounter.COUNTS.remove();
        for (Lane lane : Lane.values()) jdbc.update("delete from product." + table(lane));
    }

    @Test
    void emptyBoundsAndEmptyPagesRunOnlyFourMaxSelects() {
        measure();
        Bounds bounds = port.captureBounds();
        for (Lane lane : Lane.values()) {
            assertThat(bounds.upper(lane)).isNull();
            assertThat(port.readPage(new Criteria(lane, bounds.upper(lane), null, 3))).isEmpty();
        }
        assertCounts(4);
    }

    @Test
    void eachLanePreservesBoundaryCountsGapsAndEverySignedLongPk() {
        for (Lane lane : Lane.values()) {
            for (int count : List.of(2, 3, 4)) {
                for (int i = 0; i < count; i++) insert(lane, 10L + i * 10);
                measure();
                var first = port.readPage(new Criteria(lane, 100L, null, 3));
                assertThat(first).hasSize(Math.min(3, count));
                assertThat(first).extracting(Row::id).containsExactlyElementsOf(List.of(10L, 20L, 30L).subList(0, Math.min(3, count)));
                assertCounts(1);
                if (count == 4) assertThat(port.readPage(new Criteria(lane, 100L, 30L, 3))).extracting(Row::id).containsExactly(40L);
                jdbc.update("delete from product." + table(lane));
            }
            List<Long> ids = List.of(Long.MIN_VALUE, -900L, 0L, 43L, Long.MAX_VALUE);
            for (long id : ids) insert(lane, id);
            Bounds bounds = port.captureBounds();
            assertThat(bounds.upper(lane)).isEqualTo(Long.MAX_VALUE);
            assertThat(scan(lane, bounds.upper(lane), 2)).containsExactlyElementsOf(ids);
            measure();
            assertThat(port.readPage(new Criteria(lane, Long.MAX_VALUE, Long.MAX_VALUE, 2))).isEmpty();
            assertThat(port.readPage(new Criteria(lane, 0L, 43L, 2))).isEmpty();
            assertThat(port.readPage(new Criteria(lane, null, Long.MIN_VALUE, 2))).isEmpty();
            assertCounts(0);
        }
    }

    @Test
    void capturedUpperExcludesLaterLargerIdsAndDoesNotImplyGlobalSnapshot() {
        for (Lane lane : Lane.values()) insert(lane, 10);
        Bounds bounds = port.captureBounds();
        for (Lane lane : Lane.values()) {
            insert(lane, 20);
            assertThat(scan(lane, bounds.upper(lane), 1)).containsExactly(10L);
            assertThat(scan(lane, port.captureBounds().upper(lane), 1)).containsExactly(10L, 20L);
        }
    }

    @Test
    void normalUsdMinorUnitsNullableListPairsAndZeroAmountsRemainUnchanged() {
        for (Lane lane : Lane.values()) insert(lane, 1);
        insert(Lane.PRODUCT, 2);
        insert(Lane.PRODUCT_PRICE, 2);
        jdbc.update("update product.product set sale_price_amount=123,sale_price_currency='USD' where id=1");
        jdbc.update("update product.product set sale_price_amount=0,sale_price_currency='USD' where id=2");
        jdbc.update("update product.product_price set sale_price_amount=123,sale_price_currency='USD' where id=1");
        jdbc.update("update product.product_price set sale_price_amount=0,sale_price_currency='USD' where id=2");
        jdbc.update("update product.sku set additional_price_amount=0,additional_price_currency='USD'");
        jdbc.update("update product.sku_price set additional_price_amount=0,additional_price_currency='USD'");
        var before = snapshot();
        measure();
        var products = port.readPage(new Criteria(Lane.PRODUCT, 2L, null, 2));
        var prices = port.readPage(new Criteria(Lane.PRODUCT_PRICE, 2L, null, 2));
        for (int i = 0; i < 2; i++) {
            ProductRow product = (ProductRow) products.get(i);
            ProductPriceRow price = (ProductPriceRow) prices.get(i);
            long expected = i == 0 ? 123L : 0L;
            assertThat(product.saleAmount()).isEqualTo(expected);
            assertThat(product.saleCurrency()).isEqualTo("USD");
            assertThat(product.listAmount()).isNull();
            assertThat(product.listCurrency()).isNull();
            assertThat(price.saleAmount()).isEqualTo(expected);
            assertThat(price.saleCurrency()).isEqualTo("USD");
            assertThat(price.listAmount()).isNull();
            assertThat(price.listCurrency()).isNull();
        }
        SkuRow sku = (SkuRow) port.readPage(new Criteria(Lane.SKU, 1L, null, 1)).getFirst();
        SkuPriceRow extra = (SkuPriceRow) port.readPage(new Criteria(Lane.SKU_PRICE, 1L, null, 1)).getFirst();
        assertThat(sku.additionalAmount()).isZero();
        assertThat(sku.additionalCurrency()).isEqualTo("USD");
        assertThat(extra.additionalAmount()).isZero();
        assertThat(extra.additionalCurrency()).isEqualTo("USD");
        assertCounts(4);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void scalarProjectionRetainsUnknownEnumsNegativeMoneyAndBothBrokenNullablePairs() {
        for (Lane lane : Lane.values()) insert(lane, 1);
        jdbc.update("update product.product set sale_price_amount=-7,sale_price_currency='ZZZ',list_price_amount=11,list_price_currency=null");
        jdbc.update("update product.product_price set sale_price_amount=-8,sale_price_currency='ZZZ',list_price_amount=null,list_price_currency='ZZZ'");
        jdbc.update("update product.sku set additional_price_amount=-9,additional_price_currency='ZZZ',sale_status='UNKNOWN'");
        jdbc.update("update product.sku_price set additional_price_amount=-10,additional_price_currency='ZZZ'");
        var before = snapshot();
        measure();
        Bounds bounds = port.captureBounds();
        ProductRow product = (ProductRow) port.readPage(new Criteria(Lane.PRODUCT, bounds.product(), null, 5)).getFirst();
        SkuRow sku = (SkuRow) port.readPage(new Criteria(Lane.SKU, bounds.sku(), null, 5)).getFirst();
        ProductPriceRow price = (ProductPriceRow) port.readPage(new Criteria(Lane.PRODUCT_PRICE, bounds.productPrice(), null, 5)).getFirst();
        SkuPriceRow extra = (SkuPriceRow) port.readPage(new Criteria(Lane.SKU_PRICE, bounds.skuPrice(), null, 5)).getFirst();
        assertThat(product.saleAmount()).isEqualTo(-7L);
        assertThat(product.saleCurrency()).isEqualTo("ZZZ");
        assertThat(product.listAmount()).isEqualTo(11L);
        assertThat(product.listCurrency()).isNull();
        assertThat(price.saleAmount()).isEqualTo(-8L);
        assertThat(price.saleCurrency()).isEqualTo("ZZZ");
        assertThat(price.listAmount()).isNull();
        assertThat(price.listCurrency()).isEqualTo("ZZZ");
        assertThat(sku.additionalAmount()).isEqualTo(-9L);
        assertThat(sku.additionalCurrency()).isEqualTo("ZZZ");
        assertThat(sku.saleStatus()).isEqualTo("UNKNOWN");
        assertThat(extra.additionalAmount()).isEqualTo(-10L);
        assertThat(extra.additionalCurrency()).isEqualTo("ZZZ");
        assertCounts(8);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void stoppedSoftDeletedParentsAndEveryOrphanAndDuplicateRemainVisibleAcrossPages() {
        insert(Lane.PRODUCT, 1);
        jdbc.update("update product.product set visible=false,sale_status='STOP_SELLING',deleted_at=current_timestamp,version=17");
        for (Lane lane : List.of(Lane.SKU, Lane.PRODUCT_PRICE, Lane.SKU_PRICE)) {
            for (long id : List.of(10L, 20L, 30L, 40L)) insert(lane, id);
        }
        jdbc.update("update product.sku set sale_status='STOP_SELLING'");
        ProductRow product = (ProductRow) port.readPage(new Criteria(Lane.PRODUCT, 1L, null, 1)).getFirst();
        assertThat(product.deletedAt()).isNotNull();
        assertThat(product.visible()).isFalse();
        assertThat(product.saleStatus()).isEqualTo("STOP_SELLING");
        assertThat(product.version()).isEqualTo(17L);
        for (Lane lane : List.of(Lane.SKU, Lane.PRODUCT_PRICE, Lane.SKU_PRICE)) {
            assertThat(scan(lane, 40L, 1)).containsExactly(10L, 20L, 30L, 40L);
        }
        assertThat(((SkuRow) port.readPage(new Criteria(Lane.SKU, 40L, null, 1)).getFirst()).productId()).isEqualTo(999L);
        assertThat(((ProductPriceRow) port.readPage(new Criteria(Lane.PRODUCT_PRICE, 40L, null, 1)).getFirst()).productId()).isEqualTo(999L);
        assertThat(((SkuPriceRow) port.readPage(new Criteria(Lane.SKU_PRICE, 40L, null, 1)).getFirst()).skuId()).isEqualTo(999L);
        jdbc.update("update product.sku set product_id=1");
        var before = snapshot();
        measure();
        assertThat(port.readPage(new Criteria(Lane.SKU, 40L, null, 2))).hasSize(2);
        assertCounts(1);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void maximumPageIsBoundedForOneProductWithManySkusAndRowsStayImmutable() {
        insert(Lane.PRODUCT, 1);
        for (int i = 1; i <= 503; i++) insert(Lane.SKU, i);
        jdbc.update("update product.sku set product_id=1");
        measure();
        var page = port.readPage(new Criteria(Lane.SKU, 503L, null, 500));
        assertThat(page).hasSize(500);
        assertThat(page.getLast().id()).isEqualTo(500L);
        assertCounts(1);
        assertThatThrownBy(() -> page.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(port.readPage(new Criteria(Lane.SKU, 503L, 500L, 500))).hasSize(3);
    }

    @Test
    void rawStringsNeverExposeValues() {
        for (Lane lane : Lane.values()) insert(lane, 1234567);
        Bounds bounds = port.captureBounds();
        assertThat(bounds.toString()).isEqualTo("Bounds[redacted]");
        for (Lane lane : Lane.values()) {
            Criteria criteria = new Criteria(lane, bounds.upper(lane), null, 3);
            assertThat(criteria.toString()).isEqualTo("Criteria[redacted]");
            assertThat(port.readPage(criteria).toString()).contains("[redacted]").doesNotContain("1234567", "KRW", "999");
        }
    }

    @Test
    void preflightClassifiesMixedKrwUsdSourcesEndToEndWithoutDml() {
        for (long id : List.of(1L, 2L, 3L)) insert(Lane.PRODUCT, id);
        jdbc.update("update product.product set sale_price_amount=10000,list_price_amount=12000,list_price_currency='KRW' where id=1");
        jdbc.update("update product.product set sale_price_amount=1500,sale_price_currency='USD' where id=2");
        jdbc.update("update product.product set sale_price_currency='ZZZ',sale_status='STOP_SELLING',visible=false,deleted_at=current_timestamp where id=3");
        for (long id : List.of(11L, 12L, 21L, 22L, 41L)) insert(Lane.SKU, id);
        jdbc.update("update product.sku set product_id=1 where id in (11,12)");
        jdbc.update("update product.sku set additional_price_amount=500 where id=12");
        jdbc.update("update product.sku set product_id=2,additional_price_currency='USD' where id in (21,22)");
        jdbc.update("update product.sku set additional_price_amount=300,sale_status='STOP_SELLING' where id=22");
        for (long id : List.of(101L, 102L, 103L, 104L)) insert(Lane.PRODUCT_PRICE, id);
        jdbc.update("update product.product_price set product_id=1,sale_price_amount=10000,list_price_amount=12000,list_price_currency='KRW' where id in (101,104)");
        jdbc.update("update product.product_price set product_id=1,sale_price_amount=999,sale_price_currency='USD' where id=102");
        for (long id : List.of(1011L, 1012L, 1013L, 1021L, 1022L, 1030L, 1099L)) insert(Lane.SKU_PRICE, id);
        jdbc.update("update product.sku_price set sku_id=11 where id=1011");
        jdbc.update("update product.sku_price set sku_id=12,additional_price_amount=500 where id=1012");
        jdbc.update("update product.sku_price set sku_id=11,additional_price_amount=100,additional_price_currency='USD' where id=1013");
        jdbc.update("update product.sku_price set sku_id=21,additional_price_amount=50,additional_price_currency='USD' where id=1021");
        jdbc.update("update product.sku_price set sku_id=21 where id=1022");
        jdbc.update("update product.sku_price set sku_id=12,additional_price_amount=-1,additional_price_currency='ZZZ' where id=1030");
        var before = snapshot();
        measure();
        var report = preflight.run();
        // 상한 4 + lane마다 첫 페이지 1. 마지막 ID가 상한이면 다음 페이지는 질의 없이 빈 목록이다.
        assertCounts(8);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(report.scanned().values()).containsExactly(3L, 5L, 4L, 7L);
        var expected = new java.util.EnumMap<Finding, Long>(Finding.class);
        for (Finding finding : Finding.values()) expected.put(finding, 0L);
        expected.putAll(Map.of(Finding.LEGACY_PRODUCT_PRICE_INVALID, 1L, Finding.LEGACY_SKU_ORPHAN, 1L, Finding.PRODUCT_PRICE_ORPHAN, 1L,
            Finding.PRODUCT_PRICE_DUPLICATE, 1L, Finding.PRODUCT_PRICE_BASE_MISSING, 1L, Finding.SKU_PRICE_ORPHAN, 1L, Finding.SKU_PRICE_INVALID, 1L,
            Finding.SKU_PRICE_BASE_MISSING, 1L, Finding.SKU_PRICE_BASE_DRIFT, 1L, Finding.SKU_PRICE_EXTRA_DORMANT, 1L));
        expected.put(Finding.EXTRA_CURRENCY_ZERO_SKU_MISSING, 1L);
        assertThat(report.counts()).isEqualTo(expected);
        assertThat(report.verdict()).isEqualTo(Verdict.BLOCKED);
    }

    private List<Long> scan(Lane lane, Long upper, int size) {
        List<Long> result = new ArrayList<>();
        Long after = null;
        while (true) {
            List<Row> rows = port.readPage(new Criteria(lane, upper, after, size));
            if (rows.isEmpty()) return result;
            rows.forEach(row -> result.add(row.id()));
            after = rows.getLast().id();
        }
    }

    void measure() {
        statistics.clear();
        SqlCounter.COUNTS.set(new int[2]);
    }

    void assertCounts(int selects) {
        assertThat(SqlCounter.COUNTS.get()).containsExactly(selects, 0);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getEntityInsertCount() + statistics.getEntityUpdateCount() + statistics.getEntityDeleteCount()).isZero();
        SqlCounter.COUNTS.remove();
    }

    private List<List<Map<String, Object>>> snapshot() {
        return java.util.Arrays.stream(Lane.values()).map(lane -> jdbc.queryForList("select * from product." + table(lane) + " order by id")).toList();
    }

    static String table(Lane lane) { return lane.name().toLowerCase(Locale.ROOT); }

    void insert(Lane lane, long id) {
        switch (lane) {
            case PRODUCT -> jdbc.update("insert into product.product (id,category_id,name,sale_status,visible,sale_price_amount,sale_price_currency,"
                + "custom_order,preorder,version,created_at,updated_at) values (?,999,'fixture','ON_SALE',true,7,'KRW',false,false,0,current_timestamp,current_timestamp)", id);
            case SKU -> jdbc.update("insert into product.sku (id,product_id,option_value_key,additional_price_amount,additional_price_currency,sale_status,created_at,updated_at) "
                + "values (?,999,?,0,'KRW','ON_SALE',current_timestamp,current_timestamp)", id, Long.toString(id));
            case PRODUCT_PRICE -> jdbc.update("insert into product.product_price (id,product_id,sale_price_amount,sale_price_currency,created_at,updated_at) "
                + "values (?,999,7,'KRW',current_timestamp,current_timestamp)", id);
            case SKU_PRICE -> jdbc.update("insert into product.sku_price (id,sku_id,additional_price_amount,additional_price_currency,created_at,updated_at) "
                + "values (?,999,0,'KRW',current_timestamp,current_timestamp)", id);
        }
    }
}
