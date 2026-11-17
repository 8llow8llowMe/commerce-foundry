package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.STOP_SELLING;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Outcome;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Finding;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Verdict;
import com.commercefoundry.domainlayer.product.application.port.in.PriceBackfillUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기준통화 가격행 backfill (#20 PR-2a). 테스트 트랜잭션 없이 실제 adapter · 상품별 TX 로 돈다. PostgreSQL 하위 클래스에서도 그대로 실행한다.
 * KRW · USD 기준 상품, 정가 · 판매중지 SKU · 삭제 상품, 이미 완비된 상품의 추가 통화 행을 섞는다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:price-backfill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PriceBackfillIntegrationTest {
    static final long CATEGORY = 940010;
    /** KRW, 정가 있음, ON_SALE 0 · STOP_SELLING 500. 가격행 없음. */
    static final long KRW_PRODUCT = 940001;
    /** USD(센트), 상품 가격행만 있고 SKU 가격행 없음. */
    static final long USD_PRODUCT = 940002;
    /** 삭제된 KRW 상품. 가격행 없음. */
    static final long DELETED = 940003;
    /** 이미 완비된 KRW 상품 + 추가 통화 USD 행. */
    static final long COMPLETE = 940004;
    static final long SKU = 941000;

    @Autowired EntityManager em;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PriceBackfillUseCase backfill;
    @Autowired PriceBookRepositoryPort prices;
    @Autowired ProductAdminUseCase products;
    TransactionTemplate tx;
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactions);
        jdbc = new JdbcTemplate(dataSource);
        tx.executeWithoutResult(s -> {
            em.persist(CategoryEntity.builder().id(CATEGORY).name("backfill").visible(true).build());
            em.persist(product(KRW_PRODUCT, 1000, KRW, 1500L, ON_SALE, null));
            em.persist(sku(SKU, KRW_PRODUCT, 0, KRW, ON_SALE));
            em.persist(sku(SKU + 1, KRW_PRODUCT, 500, KRW, STOP_SELLING));
            em.persist(product(USD_PRODUCT, 2500, USD, null, ON_SALE, null));
            em.persist(sku(SKU + 2, USD_PRODUCT, 0, USD, ON_SALE));
            em.persist(product(DELETED, 0, KRW, null, STOP_SELLING, Instant.parse("2026-11-01T00:00:00Z")));
            em.persist(sku(SKU + 3, DELETED, 0, KRW, STOP_SELLING));
            em.persist(product(COMPLETE, 3000, KRW, null, ON_SALE, null));
            em.persist(sku(SKU + 4, COMPLETE, 0, KRW, ON_SALE));
        });
        tx.executeWithoutResult(s -> prices.insertAll(
            List.of(new ProductCurrencyPrice(950001, USD_PRODUCT, Money.of(2500, USD), null),
                new ProductCurrencyPrice(950002, COMPLETE, Money.of(3000, KRW), null),
                new ProductCurrencyPrice(950003, COMPLETE, Money.of(900, USD), null)),
            List.of(new SkuCurrencyPrice(950004, SKU + 4, Money.of(0, KRW)), new SkuCurrencyPrice(950005, SKU + 4, Money.of(0, USD)))));
    }

    @AfterEach
    void cleanup() {
        var ids = List.of(KRW_PRODUCT, USD_PRODUCT, DELETED, COMPLETE);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from SkuPriceEntity where skuId in (select id from SkuEntity where productId in :ids)")
                .setParameter("ids", ids).executeUpdate();
            for (String entity : List.of("ProductPriceEntity", "SkuEntity")) {
                em.createQuery("delete from " + entity + " where productId in :ids").setParameter("ids", ids).executeUpdate();
            }
            em.createQuery("delete from ProductEntity where id in :ids").setParameter("ids", ids).executeUpdate();
            em.remove(em.find(CategoryEntity.class, CATEGORY));
        });
    }

    @Test
    void dryRunCountsMissingBaseRowsWithoutWritingOrFencing() {
        var before = rowCounts();
        Report report = backfill.run(Mode.DRY_RUN);

        assertThat(report.before()).isEqualTo(Verdict.READY);
        assertThat(report.after()).isNull();
        assertThat(report.tally().count(Outcome.WOULD_FILL)).isEqualTo(3);
        assertThat(report.tally().count(Outcome.COMPLETE)).isEqualTo(1);
        assertThat(report.tally().count(Outcome.FILLED)).isZero();
        assertThat(report.tally().productPrices()).isEqualTo(2);
        assertThat(report.tally().skuPrices()).isEqualTo(4);
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(versions().values()).containsOnly(0L);
    }

    @Test
    void applyFillsOnlyMissingBaseRowsWithActualCurrencyFencesVersionAndIsIdempotent() {
        Timestamp updatedAt = jdbc.queryForObject("select updated_at from product.product where id = ?", Timestamp.class, KRW_PRODUCT);
        Report report = backfill.run(Mode.APPLY);

        assertThat(report.tally().count(Outcome.FILLED)).isEqualTo(3);
        assertThat(report.tally().count(Outcome.COMPLETE)).isEqualTo(1);
        assertThat(report.tally().count(Outcome.CONFLICT) + report.tally().count(Outcome.INVALID)).isZero();
        assertThat(report.tally().productPrices()).isEqualTo(2);
        assertThat(report.tally().skuPrices()).isEqualTo(4);
        assertThat(report.after().verdict()).isEqualTo(Verdict.READY);
        assertThat(report.after().count(Finding.PRODUCT_PRICE_BASE_MISSING)).isZero();
        assertThat(report.after().count(Finding.SKU_PRICE_BASE_MISSING)).isZero();

        assertThat(prices.findProductPrices(List.of(KRW_PRODUCT))).singleElement().satisfies(price -> {
            assertThat(price.salePrice()).isEqualTo(Money.of(1000, KRW));
            assertThat(price.listPrice()).isEqualTo(Money.of(1500, KRW));
        });
        assertThat(prices.findSkuPrices(List.of(SKU, SKU + 1))).extracting(SkuCurrencyPrice::additionalPrice)
            .containsExactly(Money.of(0, KRW), Money.of(500, KRW));
        assertThat(prices.findProductPrices(List.of(USD_PRODUCT))).extracting(ProductCurrencyPrice::id).containsExactly(950001L);
        assertThat(prices.findSkuPrices(List.of(SKU + 2))).singleElement()
            .satisfies(price -> assertThat(price.additionalPrice()).isEqualTo(Money.of(0, USD)));
        assertThat(prices.findProductPrices(List.of(DELETED))).singleElement()
            .satisfies(price -> assertThat(price.salePrice()).isEqualTo(Money.of(0, KRW)));
        assertThat(prices.findSkuPrices(List.of(SKU + 3))).hasSize(1);
        // 완비 상품의 기준 · 추가 통화 행은 그대로다
        assertThat(prices.findProductPrices(List.of(COMPLETE))).extracting(ProductCurrencyPrice::id).containsExactly(950002L, 950003L);
        assertThat(prices.findSkuPrices(List.of(SKU + 4))).extracting(SkuCurrencyPrice::id).containsExactly(950004L, 950005L);

        assertThat(versions()).containsExactlyInAnyOrderEntriesOf(Map.of(KRW_PRODUCT, 1L, USD_PRODUCT, 1L, DELETED, 1L, COMPLETE, 0L));
        assertThat(jdbc.queryForObject("select updated_at from product.product where id = ?", Timestamp.class, KRW_PRODUCT)).isEqualTo(updatedAt);
        assertThat(jdbc.queryForObject("select sale_price_amount from product.product where id = ?", Long.class, KRW_PRODUCT)).isEqualTo(1000L);

        var counts = rowCounts();
        Report again = backfill.run(Mode.APPLY);
        assertThat(again.tally().count(Outcome.COMPLETE)).isEqualTo(4);
        assertThat(again.tally().productPrices() + again.tally().skuPrices()).isZero();
        assertThat(rowCounts()).isEqualTo(counts);
        assertThat(versions()).containsExactlyInAnyOrderEntriesOf(Map.of(KRW_PRODUCT, 1L, USD_PRODUCT, 1L, DELETED, 1L, COMPLETE, 0L));
    }

    @Test
    void blockedPreflightWritesNothing() {
        // 기준통화 drift(legacy 1000 ≠ 가격행 999)는 BLOCKING 이다 — 보정하지 않고 아무것도 쓰지 않는다
        tx.executeWithoutResult(s -> prices.insertAll(List.of(new ProductCurrencyPrice(950010, KRW_PRODUCT, Money.of(999, KRW), Money.of(1500, KRW))), List.of()));
        var before = rowCounts();

        Report report = backfill.run(Mode.APPLY);

        assertThat(report.blocked()).isTrue();
        assertThat(report.tally()).isNull();
        assertThat(report.after()).isNull();
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(versions().values()).containsOnly(0L);
    }

    @Test
    void adminFormLoadedBeforeBackfillGetsConflictAndKeepsBackfilledRows() {
        backfill.run(Mode.APPLY);

        assertThatThrownBy(() -> products.updatePricing(new ProductPricingUpdateCommand(KRW_PRODUCT, 0, 99, Money.of(1200, KRW), null)))
            .isInstanceOfSatisfying(ProductException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT));
        assertThat(prices.findProductPrices(List.of(KRW_PRODUCT))).singleElement()
            .satisfies(price -> assertThat(price.salePrice()).isEqualTo(Money.of(1000, KRW)));

        products.updatePricing(new ProductPricingUpdateCommand(KRW_PRODUCT, 1, 99, Money.of(1200, KRW), null));
        assertThat(prices.findProductPrices(List.of(KRW_PRODUCT))).singleElement()
            .satisfies(price -> assertThat(price.salePrice()).isEqualTo(Money.of(1200, KRW)));
    }

    Map<String, Long> rowCounts() {
        return Map.of(
            "product_price", jdbc.queryForObject("select count(*) from product.product_price", Long.class),
            "sku_price", jdbc.queryForObject("select count(*) from product.sku_price", Long.class));
    }

    Map<Long, Long> versions() {
        var result = new java.util.HashMap<Long, Long>();
        for (long id : List.of(KRW_PRODUCT, USD_PRODUCT, DELETED, COMPLETE)) {
            result.put(id, jdbc.queryForObject("select version from product.product where id = ?", Long.class, id));
        }
        return result;
    }

    static ProductEntity product(long id, long sale, CurrencyCode currency, Long list, SaleStatus status, Instant deletedAt) {
        return ProductEntity.builder().id(id).categoryId(CATEGORY).name("backfill-" + id).visible(deletedAt == null).saleStatus(status)
            .salePriceAmount(sale).salePriceCurrency(currency).listPriceAmount(list).listPriceCurrency(list == null ? null : currency)
            .deletedAt(deletedAt).createdByMemberId(99L).build();
    }

    static SkuEntity sku(long id, long productId, long additional, CurrencyCode currency, SaleStatus status) {
        return SkuEntity.builder().id(id).productId(productId).optionValueKey(String.valueOf(id))
            .additionalPriceAmount(additional).additionalPriceCurrency(currency).saleStatus(status).weightGrams(100).build();
    }
}
