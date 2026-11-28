package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.CALLER_TRANSACTION_REQUIRED;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.PRODUCT_MISSING;
import static com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException.Reason.ROW_LIMIT_EXCEEDED;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.PriceSourceSnapshotRepositoryAdapter;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.application.mapper.CurrencyPriceMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.SkuMapperImpl;
import com.commercefoundry.domainlayer.product.application.port.out.PriceSourceSnapshotRepositoryPort;
import com.commercefoundry.domainlayer.product.application.service.processor.PriceSourceSnapshotResolver;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.resource.jdbc.spi.StatementInspector;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = {
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.commercefoundry.PriceSourceSnapshotIntegrationTest$SqlProbe",
    "spring.datasource.url=jdbc:h2:mem:price-source-snapshot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductServiceFeaturesConfig.class, QuerydslConfigurer.class, PriceSourceSnapshotRepositoryAdapter.class,
    ProductMapperImpl.class, SkuMapperImpl.class, CurrencyPriceMapperImpl.class})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PriceSourceSnapshotIntegrationTest {
    @Autowired PriceSourceSnapshotRepositoryPort port;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    JdbcTemplate jdbc;
    static final String MARKER = "snapshot-fixture-marker";
    static final List<String> TABLES = List.of("sku_price", "product_price", "sku", "product_option_value", "product");

    public static class SqlProbe implements StatementInspector {
        static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();
        static final ThreadLocal<Runnable> BEFORE_SECOND = new ThreadLocal<>();

        @Override public String inspect(String sql) {
            var seen = SQL.get();
            if (seen != null) {
                seen.add(sql.stripLeading().toLowerCase(Locale.ROOT));
                if (seen.size() == 2 && BEFORE_SECOND.get() != null) {
                    BEFORE_SECOND.get().run();
                }
            }
            return sql;
        }
    }

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clean() {
        SqlProbe.SQL.remove();
        SqlProbe.BEFORE_SECOND.remove();
        TABLES.forEach(table -> jdbc.update("delete from product." + table));
    }

    @Test
    void fourQueriesRetainHiddenDeletedStoppedRetiredAndUnprojectedPrices() {
        product(2, "USD", true);
        product(1, "KRW", false);
        sku(20, 2, "USD", true);
        sku(10, 1, "KRW", false);
        jdbc.update("insert into product.product_option_value (id,product_id,option_group_id,name,display_order,retired_at,created_at,updated_at)"
            + " values (20,2,1,'retired',0,current_timestamp,current_timestamp,current_timestamp)");
        productPrice(100, 1, "USD", 8);
        productPrice(101, 2, "USD", 9);
        skuPrice(200, 20, "USD", 0);
        var before = storedRows();

        measure();
        var ids = new ArrayList<>(List.of(2L, 1L, 2L));
        var view = port.readByProductIds(ids);
        ids.clear();
        assertSelects(4);
        assertThat(view.products()).extracting(Product::id).containsExactly(1L, 2L);
        assertThat(view.products().get(1).deletedAt()).isNotNull();
        assertThat(view.products().getFirst().visible()).isFalse();
        assertThat(view.products()).extracting(Product::salePrice).containsExactly(Money.of(0, KRW), Money.of(0, USD));
        assertThat(view.products()).allMatch(product -> product.listPrice() == null);
        assertThat(view.skus()).extracting(Sku::id).containsExactly(10L, 20L);
        assertThat(view.skus().get(1).saleStatus()).isEqualTo(SaleStatus.STOP_SELLING);
        assertThat(view.productPrices()).extracting(row -> row.salePrice()).containsExactly(Money.of(8, USD), Money.of(9, USD));
        assertThat(view.skuPrices()).singleElement().satisfies(row -> assertThat(row.additionalPrice()).isEqualTo(Money.of(0, USD)));
        assertThat(view.toString()).doesNotContain(MARKER);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> view.skus().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void emptyMissingAndNoSkuStopAtTheRequiredQuery() {
        product(1, "KRW", false);
        measure();
        assertThat(port.readByProductIds(List.of()).products()).isEmpty();
        assertSelects(0);
        measure();
        PriceSourceSnapshotRepositoryAdapterTest.assertFailure(catchThrowable(() -> port.readByProductIds(List.of(1L, 2L))), PRODUCT_MISSING);
        assertSelects(1);
        measure();
        assertThat(port.readByProductIds(List.of(1L)).skuPrices()).isEmpty();
        assertSelects(3);
    }

    @Test
    void fiftyProductsAndTwoThousandSkusStayBoundedAndOverflowStopsBeforePriceQueries() {
        var ids = LongStream.rangeClosed(1, 50).boxed().toList();
        ids.forEach(id -> product(id, "KRW", false));
        var rows = LongStream.rangeClosed(1, 2000).mapToObj(id -> new Object[]{id, id % 50 + 1, Long.toString(id)}).toList();
        jdbc.batchUpdate("insert into product.sku (id,product_id,option_value_key,additional_price_amount,additional_price_currency,"
            + "sale_status,created_at,updated_at) values (?,?,?,0,'KRW','ON_SALE',current_timestamp,current_timestamp)", rows);
        measure();
        assertThat(port.readByProductIds(ids).skus()).hasSize(2000);
        assertSelects(4);
        assertThat(SqlProbe.SQL.get()).allMatch(sql -> sql.contains("fetch first") || sql.contains(" limit "));
        sku(2001, 1, "KRW", true);
        var before = storedRows();
        measure();
        PriceSourceSnapshotRepositoryAdapterTest.assertFailure(catchThrowable(() -> port.readByProductIds(ids)), ROW_LIMIT_EXCEEDED);
        assertSelects(2);
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void currentTransactionReadRejectsMissingOrDefaultIsolationCallerWithoutSql() {
        seedPricedProduct();
        measure();
        PriceSourceSnapshotRepositoryAdapterTest.assertFailure(
            catchThrowable(() -> port.readByProductIdsInCurrentTransaction(List.of(1L))), CALLER_TRANSACTION_REQUIRED);
        var readOnlyDefault = new TransactionTemplate(manager);
        readOnlyDefault.setReadOnly(true);
        readOnlyDefault.executeWithoutResult(status -> PriceSourceSnapshotRepositoryAdapterTest.assertFailure(
            catchThrowable(() -> port.readByProductIdsInCurrentTransaction(List.of(1L))), CALLER_TRANSACTION_REQUIRED));
        assertSelects(0);
    }

    /**
     * 상세 경로(D3) — 호출자가 상품을 먼저 읽은 read-only REPEATABLE_READ TX 안에서 snapshot 을 읽으면 호출자 커넥션 하나만 쓰고(새 커넥션 0),
     * 1차 캐시의 같은 상품 Entity 를 바꾸지 않은 채 resolve 까지 성공한다. 동시 커밋 시나리오는 PostgreSQL 하위 클래스에만 있다(H2 는 표마다 snapshot).
     */
    @Test
    void currentTransactionReadUsesOnlyTheCallerConnection() throws Exception {
        seedPricedProduct();
        var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean();
        int[] activeDuringSnapshot = {-1};
        callerTemplate().executeWithoutResult(status -> {
            var caller = em.find(ProductEntity.class, 1L);
            measure();
            SqlProbe.BEFORE_SECOND.set(() -> activeDuringSnapshot[0] = pool.getActiveConnections());
            try {
                var projection = PriceSourceSnapshotResolver.resolve(port.readByProductIdsInCurrentTransaction(List.of(1L))).getFirst();
                assertSelects(4);
                assertThat(projection.product().salePrice()).isEqualTo(Money.of(1_000, KRW));
                assertThat(projection.skus()).singleElement().satisfies(sku -> assertThat(sku.additionalPrice()).isEqualTo(Money.zero(KRW)));
                assertThat(em.contains(caller)).isTrue();
            } finally {
                SqlProbe.BEFORE_SECOND.remove();
            }
        });
        assertThat(activeDuringSnapshot[0]).as("호출자 커넥션 하나만 빌린다").isEqualTo(1);
    }

    /** {@code ProductWebFacade#getProduct} 와 같은 read-only REPEATABLE_READ 호출자 TX. */
    TransactionTemplate callerTemplate() {
        var template = new TransactionTemplate(manager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    /** 판매 중지 상품 1(KRW 1,000)과 판매 중지 SKU 10(추가금 0), 그와 같은 값의 기준통화 가격행 — drift 가 없는 정상 원천이다. */
    void seedPricedProduct() {
        product(1, "KRW", false);
        jdbc.update("update product.product set sale_price_amount=1000 where id=1");
        sku(10, 1, "KRW", true);
        productPrice(100, 1, "KRW", 1_000);
        skuPrice(200, 10, "KRW", 0);
    }

    void measure() {
        SqlProbe.SQL.set(new ArrayList<>());
    }

    void assertSelects(int count) {
        assertThat(SqlProbe.SQL.get()).hasSize(count).allMatch(sql -> sql.startsWith("select"));
    }

    List<?> storedRows() {
        return TABLES.stream().map(table -> jdbc.queryForList("select * from product." + table + " order by id")).toList();
    }

    void product(long id, String currency, boolean deleted) {
        jdbc.update("insert into product.product (id,category_id,name,sale_status,visible,sale_price_amount,sale_price_currency,"
                + "custom_order,preorder,version,created_at,updated_at,deleted_at) "
                + "values (?,999,?,'STOP_SELLING',false,0,?,false,false,0,current_timestamp,current_timestamp,?)",
            id, MARKER, currency, deleted ? java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z")) : null);
    }

    void sku(long id, long productId, String currency, boolean stopped) {
        jdbc.update("insert into product.sku (id,product_id,option_value_key,additional_price_amount,additional_price_currency,"
            + "sale_status,created_at,updated_at) values (?,?,?,0,?,?,current_timestamp,current_timestamp)",
            id, productId, Long.toString(id), currency, stopped ? "STOP_SELLING" : "ON_SALE");
    }

    void productPrice(long id, long owner, String currency, long amount) {
        jdbc.update("insert into product.product_price (id,product_id,sale_price_amount,sale_price_currency,created_at,updated_at)"
            + " values (?,?,?,?,current_timestamp,current_timestamp)", id, owner, amount, currency);
    }

    void skuPrice(long id, long owner, String currency, long amount) {
        jdbc.update("insert into product.sku_price (id,sku_id,additional_price_amount,additional_price_currency,created_at,updated_at)"
            + " values (?,?,?,?,current_timestamp,current_timestamp)", id, owner, amount, currency);
    }
}
