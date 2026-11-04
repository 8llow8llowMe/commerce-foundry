package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.PriceBookRepositoryAdapter;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import com.commercefoundry.domainlayer.product.application.mapper.CurrencyPriceMapperImpl;
import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.regex.Pattern;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** H2와 PostgreSQL 하위 클래스에서 같은 passive 저장 계약을 실행한다. */
@DataJpaTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.commercefoundry.PriceBookStorageIntegrationTest$SelectCounter",
    "spring.datasource.url=jdbc:h2:mem:price-book;MODE=PostgreSQL;"
    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductServiceFeaturesConfig.class, QuerydslConfigurer.class, PriceBookRepositoryAdapter.class, CurrencyPriceMapperImpl.class})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PriceBookStorageIntegrationTest {
    /** SQL 원문은 보관하지 않고 측정 스레드의 Hibernate SQL 준비 단계에서 SELECT 수만 관찰한다. */
    public static class SelectCounter implements StatementInspector {
        private static final Pattern SELECT = Pattern.compile("\\bselect\\b", Pattern.CASE_INSENSITIVE);
        private static final ThreadLocal<Integer> COUNT = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            Integer count = COUNT.get();
            if (count != null && SELECT.matcher(sql).find()) {
                COUNT.set(count + 1);
            }
            return sql;
        }
    }

    @Autowired PriceBookRepositoryPort port;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    TransactionTemplate tx;
    JdbcTemplate jdbc;
    Statistics statistics;

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(manager);
        jdbc = new JdbcTemplate(dataSource);
        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @AfterEach
    void clean() {
        SelectCounter.COUNT.remove();
        jdbc.update("delete from product.sku_price");
        jdbc.update("delete from product.product_price");
    }

    @Test
    void hibernateSubmitsNoSelectForInsertsAndBulkReadsAllCurrenciesInStableOrder() {
        SelectCounter.COUNT.set(0);
        tx.executeWithoutResult(s -> port.insertAll(List.of(product(2, 20, USD), product(1, 10, KRW)), List.of(sku(4, 2, USD), sku(3, 1, KRW))));
        assertThat(statistics.getEntityInsertCount()).isEqualTo(4);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getQueryExecutionCount()).isZero();
        assertThat(SelectCounter.COUNT.get()).isZero();
        SelectCounter.COUNT.remove();
        statistics.clear();
        tx.executeWithoutResult(s -> {
            assertThat(port.findProductPrices(List.of(20L, 10L))).containsExactly(product(1, 10, KRW), product(2, 20, USD));
            assertThat(port.findSkuPrices(List.of(2L, 1L))).containsExactly(sku(3, 1, KRW), sku(4, 2, USD));
        });
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        SelectCounter.COUNT.set(0);
        assertThat(port.findProductPrices(List.of(999999L))).isEmpty();
        assertThat(SelectCounter.COUNT.get()).isEqualTo(1);
        SelectCounter.COUNT.remove();
        statistics.clear();
        assertThat(port.findProductPrices(List.of())).isEmpty();
        assertThat(port.findSkuPrices(List.of())).isEmpty();
        tx.executeWithoutResult(s -> port.updateAll(List.of(), List.of()));
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void managedUpdatesBulkLoadOnceAndRetainIdsCreatedAtAndUnmentionedAdditionalCurrency() {
        tx.executeWithoutResult(s -> port.insertAll(List.of(product(1, 10, KRW), product(2, 10, USD)), List.of(sku(3, 1, KRW), sku(4, 1, USD))));
        var productBefore = jdbc.queryForMap("select * from product.product_price where id = 1");
        var skuBefore = jdbc.queryForMap("select * from product.sku_price where id = 3");
        statistics.clear();
        tx.executeWithoutResult(s -> port.updateAll(List.of(new ProductCurrencyPrice(1, 10, Money.of(250, KRW), Money.of(500, KRW))),
            List.of(new SkuCurrencyPrice(3, 1, Money.of(50, KRW)))));
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isEqualTo(2);
        assertThat(jdbc.queryForMap("select * from product.product_price where id = 1")).containsEntry("id", productBefore.get("id"))
            .containsEntry("created_at", productBefore.get("created_at")).containsEntry("sale_price_amount", 250L);
        assertThat(jdbc.queryForMap("select * from product.sku_price where id = 3")).containsEntry("created_at", skuBefore.get("created_at"));
        assertThat(port.findProductPrices(List.of(10L))).contains(product(2, 10, USD));
        assertThat(port.findSkuPrices(List.of(1L))).contains(sku(4, 1, USD));
    }

    @Test
    void unknownRowsOrIdentityChangesFailWithoutMutatingAnyRows() {
        tx.executeWithoutResult(s -> port.insertAll(List.of(product(1, 10, KRW)), List.of(sku(3, 1, KRW))));
        for (var input : List.of(product(1, 99, KRW), product(1, 10, USD), product(99, 10, KRW))) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> port.updateAll(List.of(input), List.of())))
                .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> port.updateAll(List.of(new ProductCurrencyPrice(1, 10, Money.of(999, KRW), null)),
            List.of(sku(3, 99, KRW))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(port.findProductPrices(List.of(10L))).containsExactly(product(1, 10, KRW));
        assertThatThrownBy(() -> port.insertAll(List.of(product(2, 10, USD)), List.of())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void parentlessStorageIsPassiveAndCallerRollbackRemovesBothFlushedKinds() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            port.insertAll(List.of(product(1, 10, KRW)), List.of(sku(3, 1, KRW)));
            assertThat(em.find(ProductPriceEntity.class, 1L).isNew()).isFalse();
            assertThat(em.find(SkuPriceEntity.class, 3L).getCreatedAt()).isNotNull();
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(port.findProductPrices(List.of(10L))).isEmpty();
        assertThat(port.findSkuPrices(List.of(1L))).isEmpty();
    }

    @Test
    void rawSqlRejectsInvalidMoneyPairsRangesCurrenciesAndDuplicates() {
        for (String values : List.of("1, 10, -1, 'KRW', null, null", "1, 10, null, 'KRW', null, null", "1, 10, 0, null, null, null",
            "1, 10, 0, 'KRW', -1, 'KRW'", "1, 10, 0, 'KRW', 1, null", "1, 10, 0, 'KRW', null, 'KRW'",
            "1, 10, 0, 'KRW', 1, 'USD'", "1, 10, 1, 'KRW', 1, 'KRW'", "1, 10, 0, 'XXX', null, null", "0, 10, 0, 'KRW', null, null")) {
            assertThatThrownBy(() -> jdbc.update("insert into product.product_price "
                + "(id,product_id,sale_price_amount,sale_price_currency,list_price_amount,list_price_currency,created_at,updated_at) values ("
                + values + ",current_timestamp,current_timestamp)" )).isInstanceOf(RuntimeException.class);
        }
        tx.executeWithoutResult(s -> port.insertAll(List.of(product(1, 10, KRW)), List.of(sku(3, 1, KRW))));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> port.insertAll(List.of(product(2, 10, KRW)), List.of())))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> port.insertAll(List.of(), List.of(sku(4, 1, KRW)))))
            .isInstanceOf(RuntimeException.class);
        for (String values : List.of("4,1,-1,'KRW'", "4,1,null,'KRW'", "4,1,0,'XXX'", "4,1,0,null", "4,0,0,'KRW'")) {
            assertThatThrownBy(() -> jdbc.update("insert into product.sku_price "
                + "(id,sku_id,additional_price_amount,additional_price_currency,created_at,updated_at) values ("
                + values + ",current_timestamp,current_timestamp)" )).isInstanceOf(RuntimeException.class);
        }
    }

    private static ProductCurrencyPrice product(long id, long productId, com.commercefoundry.shared.commerce.CurrencyCode currency) {
        return new ProductCurrencyPrice(id, productId, Money.of(100, currency), null);
    }

    private static SkuCurrencyPrice sku(long id, long skuId, com.commercefoundry.shared.commerce.CurrencyCode currency) {
        return new SkuCurrencyPrice(id, skuId, Money.zero(currency));
    }
}
