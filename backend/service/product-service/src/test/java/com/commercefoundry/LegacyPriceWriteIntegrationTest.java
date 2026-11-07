package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.PriceBookRepositoryAdapter;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import com.commercefoundry.domainlayer.product.application.mapper.CurrencyPriceMapperImpl;
import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.application.service.processor.LegacyPriceWriteProcessor;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionFactory;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 저장 adapter와 트랜잭션을 통한 B1b A 계약. PostgreSQL 하위 클래스에서도 그대로 실행한다. */
@DataJpaTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.datasource.url=jdbc:h2:mem:legacy-price-write;MODE=PostgreSQL;"
    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductServiceFeaturesConfig.class, QuerydslConfigurer.class, PriceBookRepositoryAdapter.class,
    CurrencyPriceMapperImpl.class, LegacyPriceWriteProcessor.class})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegacyPriceWriteIntegrationTest {
    @Autowired PriceBookRepositoryPort prices;
    @Autowired LegacyPriceWriteProcessor writer;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean SnowflakeIdGenerator snowflake;
    TransactionTemplate tx;
    JdbcTemplate jdbc;
    Statistics statistics;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(manager);
        jdbc = new JdbcTemplate(dataSource);
        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var ids = new AtomicLong(1000);
        when(snowflake.generateId()).thenAnswer(invocation -> ids.incrementAndGet());
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from product.sku_price");
        jdbc.update("delete from product.product_price");
    }

    @Test
    void allThreeMethodsRequireCallerTransaction() {
        var before = snapshot(100, sku(10, 0, SaleStatus.ON_SALE));
        var ids = new AtomicLong(9000);
        var plan = com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan
            .prepareNew(before, List.of(), List.of(), ids::incrementAndGet);
        assertThatThrownBy(() -> writer.prepare(before, before)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> writer.prepareNew(before)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> writer.write(plan)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void prepareUsesTwoBulkReadsWithoutWritingEvenForManySkus() {
        var skus = java.util.stream.LongStream.rangeClosed(10, 109).mapToObj(id -> sku(id, 0, SaleStatus.ON_SALE)).toArray(Sku[]::new);
        var after = snapshot(100, skus);
        statistics.clear();
        var plan = tx.execute(status -> writer.prepareNew(after));
        assertThat(plan.productInserts()).hasSize(1);
        assertThat(plan.skuInserts()).hasSize(100);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(prices.findProductPrices(List.of(1L))).isEmpty();
        assertThat(prices.findSkuPrices(List.of(10L, 109L))).isEmpty();
    }

    @Test
    void existingPrepareUsesTwoBulkReadsAndUpdateReloadsOnlyChangedRow() {
        var skus = java.util.stream.LongStream.rangeClosed(10, 109).mapToObj(id -> sku(id, 0, SaleStatus.ON_SALE)).toArray(Sku[]::new);
        var before = snapshot(100, skus);
        var after = snapshot(200, skus);
        tx.executeWithoutResult(status -> prices.insertAll(List.of(product(1, 100, KRW)),
            java.util.Arrays.stream(skus).map(sku -> price(2000 + sku.id(), sku.id(), 0, KRW)).toList()));
        statistics.clear();
        tx.executeWithoutResult(status -> {
            var plan = writer.prepare(before, after);
            assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
            assertThat(statistics.getEntityInsertCount()).isZero();
            assertThat(statistics.getEntityUpdateCount()).isZero();
            assertThat(plan.productUpdates()).containsExactly(product(1, 200, KRW));
            assertThat(plan.skuUpdates()).isEmpty();
            writer.write(plan);
        });
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(3);
        assertThat(statistics.getEntityUpdateCount()).isEqualTo(1);
    }

    @Test
    void preservesOtherCurrencyAndExistingIdsAndCreatedAt() {
        tx.executeWithoutResult(status -> prices.insertAll(List.of(product(1, 100, KRW), product(2, 300, USD)),
            List.of(price(3, 10, 0, KRW), price(4, 10, 0, USD))));
        var baseCreated = jdbc.queryForObject("select created_at from product.product_price where id = 1", java.sql.Timestamp.class);
        var extraCreated = jdbc.queryForObject("select created_at from product.product_price where id = 2", java.sql.Timestamp.class);
        var extraSkuCreated = jdbc.queryForObject("select created_at from product.sku_price where id = 4", java.sql.Timestamp.class);
        var before = snapshot(100, sku(10, 0, SaleStatus.ON_SALE), sku(11, 5, SaleStatus.STOP_SELLING));
        var after = snapshot(200, sku(10, 0, SaleStatus.ON_SALE), sku(11, 8, SaleStatus.STOP_SELLING));
        tx.executeWithoutResult(status -> {
            var plan = writer.prepare(before, after);
            assertThat(plan.productInserts()).isEmpty();
            assertThat(plan.productUpdates()).containsExactly(product(1, 200, KRW));
            assertThat(plan.skuInserts()).containsExactly(price(1001, 11, 8, KRW));
            writer.write(plan);
        });
        assertThat(prices.findProductPrices(List.of(1L))).containsExactly(product(1, 200, KRW), product(2, 300, USD));
        assertThat(prices.findSkuPrices(List.of(10L, 11L)))
            .containsExactly(price(3, 10, 0, KRW), price(4, 10, 0, USD), price(1001, 11, 8, KRW));
        assertThat(jdbc.queryForObject("select created_at from product.product_price where id = 1", java.sql.Timestamp.class)).isEqualTo(baseCreated);
        assertThat(jdbc.queryForObject("select created_at from product.product_price where id = 2", java.sql.Timestamp.class)).isEqualTo(extraCreated);
        assertThat(jdbc.queryForObject("select created_at from product.sku_price where id = 4", java.sql.Timestamp.class)).isEqualTo(extraSkuCreated);
    }

    @Test
    void updateFailureAfterFlushedInsertRollsBackBothKinds() {
        tx.executeWithoutResult(status -> prices.insertAll(List.of(product(1, 100, KRW)), List.of()));
        var before = snapshot(100, sku(10, 0, SaleStatus.ON_SALE));
        var after = snapshot(200, sku(10, 0, SaleStatus.ON_SALE));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var plan = writer.prepare(before, after);
            em.remove(em.find(ProductPriceEntity.class, 1L));
            em.flush();
            writer.write(plan);
        })).isInstanceOf(IllegalStateException.class);
        assertThat(prices.findProductPrices(List.of(1L))).containsExactly(product(1, 100, KRW));
        assertThat(prices.findSkuPrices(List.of(10L))).isEmpty();
    }

    private static LegacyPriceSnapshot snapshot(long sale, Sku... skus) {
        return new LegacyPriceSnapshot(1, Money.of(sale, KRW), null, List.of(skus));
    }

    private static Sku sku(long id, long additional, SaleStatus status) {
        return new Sku(id, 1, SkuOptionKey.of(List.of(id)), Money.of(additional, KRW), status, null, null);
    }

    private static ProductCurrencyPrice product(long id, long sale, com.commercefoundry.shared.commerce.CurrencyCode currency) {
        return new ProductCurrencyPrice(id, 1, Money.of(sale, currency), null);
    }

    private static SkuCurrencyPrice price(long id, long skuId, long additional, com.commercefoundry.shared.commerce.CurrencyCode currency) {
        return new SkuCurrencyPrice(id, skuId, Money.of(additional, currency));
    }
}
