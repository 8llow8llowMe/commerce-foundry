package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.STOP_SELLING;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuPriceEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.application.command.OptionRetirementCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand;
import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuAdminRepositoryPort;
import com.commercefoundry.domainlayer.product.application.service.processor.LegacyPriceWriteProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductWriteProcessor;
import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** B writer contracts run against H2 and the PostgreSQL subclass without a surrounding test transaction. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:legacy-price-writers;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyPriceWritersIntegrationTest {
    static final long ID = 920001, NEW = 920002, CATEGORY = 920010, GROUP = 920020, VALUE = 920030, SKU = 920040;
    @Autowired EntityManager em;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductAdminUseCase products;
    @Autowired SkuAdminUseCase skus;
    @Autowired ProductWriteProcessor create;
    @MockitoSpyBean PriceBookRepositoryPort prices;
    @MockitoSpyBean LegacyPriceWriteProcessor coordinator;
    @MockitoSpyBean SkuAdminRepositoryPort writes;
    TransactionTemplate tx;
    JdbcTemplate jdbc;

    enum Writer { CREATE, PRICING, ADD, BULK, RETIRE }

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactions);
        jdbc = new JdbcTemplate(dataSource);
        tx.executeWithoutResult(s -> {
            em.persist(CategoryEntity.builder().id(CATEGORY).name("B fixture").visible(true).build());
            em.persist(ProductEntity.builder().id(ID).categoryId(CATEGORY).name("legacy").visible(true).saleStatus(ON_SALE)
                .salePriceAmount(1000).salePriceCurrency(KRW).createdByMemberId(99L).build());
            em.persist(ProductOptionGroupEntity.builder().id(GROUP).productId(ID).name("size").displayOrder(0).build());
            for (int i = 0; i < 3; i++) {
                em.persist(ProductOptionValueEntity.builder().id(VALUE + i).productId(ID).optionGroupId(GROUP)
                    .name("size" + i).displayOrder(i).build());
            }
            for (int i = 0; i < 2; i++) {
                em.persist(SkuEntity.builder().id(SKU + i).productId(ID).optionValueKey(String.valueOf(VALUE + i))
                    .additionalPriceAmount(0).additionalPriceCurrency(KRW).saleStatus(ON_SALE).weightGrams(100).build());
            }
            em.persist(StockEntity.builder().skuId(SKU).stockManaged(true).availableQuantity(7).build());
        });
        clearInvocations(prices, coordinator);
    }

    @AfterEach
    void cleanup() {
        reset(prices, coordinator, writes);
        tx.executeWithoutResult(s -> {
            for (String entity : List.of("StockAdjustmentHistoryEntity", "StockEntity", "SkuPriceEntity")) {
                em.createQuery("delete from " + entity + " where skuId in (select id from SkuEntity where productId in :ids)")
                    .setParameter("ids", List.of(ID, NEW)).executeUpdate();
            }
            for (String entity : List.of("ProductPriceEntity", "SkuEntity", "ProductOptionValueEntity", "ProductOptionGroupEntity", "ProductCategoryEntity")) {
                em.createQuery("delete from " + entity + " where productId in :ids").setParameter("ids", List.of(ID, NEW)).executeUpdate();
            }
            em.createQuery("delete from ProductEntity where id in :ids").setParameter("ids", List.of(ID, NEW)).executeUpdate();
            em.remove(em.find(CategoryEntity.class, CATEGORY));
        });
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void allFiveWritersCommitCompleteBasePricesWithoutTouchingExistingStock(Writer writer) {
        assertPreparationOrder(writer);
        mutate(writer);
        long id = writer == Writer.CREATE ? NEW : ID;
        assertProjection(id);
        tx.executeWithoutResult(s -> {
            assertThat(em.find(StockEntity.class, SKU).getAvailableQuantity()).isEqualTo(7);
            assertThat(em.find(ProductEntity.class, id).getVersion()).isEqualTo(writer == Writer.CREATE ? 0L : 1L);
            if (writer == Writer.CREATE || writer == Writer.ADD) {
                assertThat(em.createQuery("select count(h) from StockAdjustmentHistoryEntity h where h.skuId in "
                    + "(select id from SkuEntity where productId = :id)", Long.class).setParameter("id", id).getSingleResult()).isEqualTo(1);
            }
            if (writer == Writer.RETIRE) {
                assertThat(em.find(SkuEntity.class, SKU).getSaleStatus()).isEqualTo(STOP_SELLING);
                assertThat(em.find(ProductOptionValueEntity.class, VALUE).getRetiredAt()).isNotNull();
            }
        });
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void failureAfterRealPriceFlushRollsBackParentChildrenAndPriceRowsWithoutReclassification(Writer writer) {
        var failure = new DataIntegrityViolationException("injected price storage failure");
        tx.executeWithoutResult(s -> doAnswer(call -> {
            call.callRealMethod(); // Real INSERTs and flush must happen before the injected failure.
            assertProjectionInTransaction(writer == Writer.CREATE ? NEW : ID);
            throw failure;
        }).when(prices).insertAll(any(), any()));

        assertThatThrownBy(() -> mutate(writer)).isSameAs(failure);
        assertRolledBack();
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void downstreamFailureAfterAllWriterWorkRollsBackPricesStockAndHistoryTogether(Writer writer) {
        var failure = new IllegalStateException("injected downstream failure");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            mutate(writer);
            em.flush();
            assertProjectionInTransaction(writer == Writer.CREATE ? NEW : ID);
            if (writer == Writer.CREATE || writer == Writer.ADD) {
                long id = writer == Writer.CREATE ? NEW : ID;
                assertThat(em.createQuery("select count(h) from StockAdjustmentHistoryEntity h where h.skuId in "
                    + "(select id from SkuEntity where productId = :id)", Long.class).setParameter("id", id).getSingleResult()).isEqualTo(1);
            }
            throw failure;
        })).isSameAs(failure);
        assertRolledBack();
    }

    @ParameterizedTest
    @EnumSource(value = Writer.class, names = {"PRICING", "ADD", "BULK", "RETIRE"})
    void driftIsAnInternalErrorAndRollsBackTheClaimedVersion(Writer writer) {
        tx.executeWithoutResult(s -> prices.insertAll(List.of(new ProductCurrencyPrice(930001, ID, Money.of(999, KRW), null)), List.of()));
        assertThatThrownBy(() -> mutate(writer)).isInstanceOf(IllegalStateException.class);
        assertThat(prices.findProductPrices(List.of(ID))).containsExactly(new ProductCurrencyPrice(930001, ID, Money.of(999, KRW), null));
        assertThat(prices.findSkuPrices(List.of(SKU, SKU + 1))).isEmpty();
        assertLegacyUnchanged();
    }

    @ParameterizedTest
    @EnumSource(value = Writer.class, names = {"BULK", "RETIRE"})
    void removingLastAdditionalCurrencyZeroIsRequestErrorAndRollsBack(Writer writer) {
        additionalPrices();
        // Both base SKU amounts are zero, so only the additional USD set rejects stopping SKU.
        assertThatThrownBy(() -> {
            if (writer == Writer.BULK) skus.replace(bulk(0, STOP_SELLING, 0));
            else mutate(writer);
        }).isInstanceOfSatisfying(SkuAdminException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SkuAdminErrorCode.PRICE_INVALID));
        assertLegacyUnchanged();
        assertThat(prices.findProductPrices(List.of(ID))).hasSize(1); // Failed base-row repair was not committed.
        assertThat(prices.findSkuPrices(List.of(SKU, SKU + 1))).hasSize(2);
    }

    @Test
    void restartingStoppedSkuWithOnlyNonzeroAdditionalCurrencyPriceIsRejected() {
        tx.executeWithoutResult(s -> {
            em.createQuery("update SkuEntity set saleStatus = :status where productId = :id")
                .setParameter("status", STOP_SELLING).setParameter("id", ID).executeUpdate();
            prices.insertAll(List.of(new ProductCurrencyPrice(930001, ID, Money.of(2000, USD), null)),
                List.of(new SkuCurrencyPrice(930002, SKU, Money.of(5, USD))));
        });
        var request = new SkuBulkUpdateCommand(ID, 0, 99, List.of(
            new SkuBulkUpdateCommand.Item(SKU, Money.of(0, KRW), ON_SALE, 100, null),
            new SkuBulkUpdateCommand.Item(SKU + 1, Money.of(0, KRW), STOP_SELLING, 100, null)));

        assertThatThrownBy(() -> skus.replace(request)).isInstanceOfSatisfying(SkuAdminException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SkuAdminErrorCode.PRICE_INVALID));
        assertThat(prices.findProductPrices(List.of(ID))).containsExactly(new ProductCurrencyPrice(930001, ID, Money.of(2000, USD), null));
        assertThat(prices.findSkuPrices(List.of(SKU, SKU + 1))).containsExactly(new SkuCurrencyPrice(930002, SKU, Money.of(5, USD)));
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductEntity.class, ID).getVersion()).isZero();
            assertThat(em.find(SkuEntity.class, SKU).getSaleStatus()).isEqualTo(STOP_SELLING);
            assertThat(em.find(SkuEntity.class, SKU + 1).getSaleStatus()).isEqualTo(STOP_SELLING);
        });
    }

    @Test
    void noOpsDoNotQueryPricesOrAllocateRepairsButBulkIdenticalValuesStillClaimVersion() {
        assertThat(products.updatePricing(pricing(0, 1000)).version()).isZero();
        verifyNoInteractions(prices, coordinator);
        tx.executeWithoutResult(s -> em.createQuery("update ProductOptionValueEntity set retiredAt = :at where id = :id")
            .setParameter("at", Instant.parse("2026-01-01T00:00:00Z")).setParameter("id", VALUE + 2).executeUpdate());
        assertThat(skus.retireOptions(new OptionRetirementCommand(ID, 0, 99, Set.of(), Set.of(VALUE + 2))).version()).isZero();
        verifyNoInteractions(prices, coordinator);
        var identical = new SkuBulkUpdateCommand(ID, 0, 99, List.of(
            new SkuBulkUpdateCommand.Item(SKU, Money.of(0, KRW), ON_SALE, 100, null),
            new SkuBulkUpdateCommand.Item(SKU + 1, Money.of(0, KRW), ON_SALE, 100, null)));
        assertThat(skus.replace(identical).version()).isEqualTo(1);
        assertProjection(ID);
    }

    @Test
    void repairsStoppedAndRetiredBaseSkusAndPreservesPartialAdditionalCurrency() {
        additionalPrices();
        skus.retireOptions(new OptionRetirementCommand(ID, 0, 99, Set.of(), Set.of(VALUE + 1)));
        var extraBefore = prices.findSkuPrices(List.of(SKU, SKU + 1)).stream().filter(p -> p.additionalPrice().currency() == USD).toList();
        var productBefore = prices.findProductPrices(List.of(ID)).stream().filter(p -> p.salePrice().currency() == USD).toList();
        skus.add(add(1));
        assertProjection(ID);
        var allIds = tx.execute(s -> em.createQuery("select id from SkuEntity where productId = :id", Long.class).setParameter("id", ID).getResultList());
        assertThat(prices.findSkuPrices(allIds).stream().filter(p -> p.additionalPrice().currency() == USD)).containsExactlyElementsOf(extraBefore);
        assertThat(prices.findProductPrices(List.of(ID)).stream().filter(p -> p.salePrice().currency() == USD)).containsExactlyElementsOf(productBefore);
        assertThat(allIds).hasSize(3);
    }

    @Test
    void pricingAndBulkPreservePriceIdsCreatedAtAndAdditionalCurrencyRows() {
        additionalPrices();
        products.updatePricing(pricing(0, 1200));
        var beforeProducts = tx.execute(s -> em.createQuery("from ProductPriceEntity where productId = :id order by id", ProductPriceEntity.class)
            .setParameter("id", ID).getResultList());
        var beforeSkus = tx.execute(s -> em.createQuery("from SkuPriceEntity where skuId in :ids order by id", SkuPriceEntity.class)
            .setParameter("ids", List.of(SKU, SKU + 1)).getResultList());
        products.updatePricing(pricing(1, 1400));
        skus.replace(bulk(2, ON_SALE, 30));
        assertProjection(ID);
        tx.executeWithoutResult(s -> {
            for (var row : beforeProducts) {
                var after = em.find(ProductPriceEntity.class, row.getId());
                assertThat(after.getCreatedAt()).isEqualTo(row.getCreatedAt());
                if (row.getSalePriceCurrency() == USD) assertThat(after).usingRecursiveComparison().isEqualTo(row);
            }
            for (var row : beforeSkus) {
                var after = em.find(SkuPriceEntity.class, row.getId());
                assertThat(after.getCreatedAt()).isEqualTo(row.getCreatedAt());
                if (row.getAdditionalPriceCurrency() == USD) assertThat(after).usingRecursiveComparison().isEqualTo(row);
            }
        });
    }

    @ParameterizedTest
    @EnumSource(value = CurrencyCode.class, names = {"KRW", "USD"})
    void createAndLegacyRepairUseActualBaseCurrency(CurrencyCode currency) {
        tx.executeWithoutResult(s -> {
            create.create(createCommand(currency), "<p>body</p>", List.of(CATEGORY), NEW);
            em.createQuery("update ProductEntity set salePriceCurrency = :c where id = :id").setParameter("c", currency).setParameter("id", ID).executeUpdate();
            em.createQuery("update SkuEntity set additionalPriceCurrency = :c where productId = :id").setParameter("c", currency).setParameter("id", ID).executeUpdate();
        });
        products.updatePricing(new ProductPricingUpdateCommand(ID, 0, 99, Money.of(1500, currency), null));
        assertProjection(NEW);
        assertProjection(ID);
        assertThat(prices.findProductPrices(List.of(ID, NEW))).allSatisfy(p -> assertThat(p.salePrice().currency()).isEqualTo(currency));
    }

    @Test
    void afterOverflowIsProductRequestErrorAndNotInternalDrift() {
        tx.executeWithoutResult(s -> em.createQuery("update SkuEntity set additionalPriceAmount = 1 where id = :id")
            .setParameter("id", SKU + 1).executeUpdate());
        assertThatThrownBy(() -> products.updatePricing(pricing(0, Long.MAX_VALUE)))
            .isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED));
        assertThat(prices.findProductPrices(List.of(ID))).isEmpty();
        assertThat(tx.<Long>execute(s -> em.find(ProductEntity.class, ID).getVersion())).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Writer.class, names = {"ADD", "BULK"})
    void skuAfterOverflowIsRequestErrorAndRollsBackInsertedSkuOrClaim(Writer writer) {
        tx.executeWithoutResult(s -> em.createQuery("update ProductEntity set salePriceAmount = :amount where id = :id")
            .setParameter("amount", Long.MAX_VALUE).setParameter("id", ID).executeUpdate());
        assertThatThrownBy(() -> mutate(writer)).isInstanceOfSatisfying(SkuAdminException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SkuAdminErrorCode.PRICE_INVALID));
        assertThat(prices.findProductPrices(List.of(ID))).isEmpty();
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductEntity.class, ID).getVersion()).isZero();
            assertThat(em.find(SkuEntity.class, SKU + 1).getAdditionalPriceAmount()).isZero();
            assertThat(em.createQuery("select count(s) from SkuEntity s where productId = :id", Long.class).setParameter("id", ID).getSingleResult()).isEqualTo(2);
        });
    }

    @Test
    void createAfterOverflowIsProductRequestErrorAndRollsBackFlushedNewRows() {
        var command = ProductCreateCommand.builder().name("overflow").categoryId(CATEGORY).categoryIds(List.of()).saleStatus(ON_SALE)
            .salePrice(Money.of(Long.MAX_VALUE, KRW)).createdByMemberId(99)
            .optionGroups(List.of(new ProductCreateCommand.OptionGroup("size", List.of("S", "M"))))
            .skus(List.of(new ProductCreateCommand.Sku(List.of("S"), Money.of(0, KRW), ON_SALE, null, null, null),
                new ProductCreateCommand.Sku(List.of("M"), Money.of(1, KRW), ON_SALE, null, null, null))).build();
        create.validate(command); // The pre-existing validation accepts this; the new after validation classifies overflow.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> create.create(command, null, List.of(CATEGORY), NEW)))
            .isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED));
        assertRolledBack();
    }

    @Test
    void oldVersionMembershipAndRetiredValidationPrecedePriceReads() {
        assertThatThrownBy(() -> products.updatePricing(pricing(7, 1500))).isInstanceOfSatisfying(ProductException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT));
        assertThatThrownBy(() -> skus.add(add(7))).isInstanceOfSatisfying(SkuAdminException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SkuAdminErrorCode.VERSION_CONFLICT));
        assertThatThrownBy(() -> skus.replace(new SkuBulkUpdateCommand(ID, 0, 99, List.of())))
            .isInstanceOfSatisfying(SkuAdminException.class, e -> assertThat(e.getErrorCode()).isEqualTo(SkuAdminErrorCode.SKU_SET_INVALID));
        tx.executeWithoutResult(s -> em.createQuery("update ProductOptionValueEntity set retiredAt = :at where id = :id")
            .setParameter("at", Instant.parse("2026-01-01T00:00:00Z")).setParameter("id", VALUE).executeUpdate());
        assertThatThrownBy(() -> skus.replace(bulk(0, ON_SALE, 0))).isInstanceOfSatisfying(SkuAdminException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SkuAdminErrorCode.RETIRED_OPTION_ON_SALE));
        verifyNoInteractions(prices, coordinator);
        assertThat(tx.<Long>execute(s -> em.find(ProductEntity.class, ID).getVersion())).isZero();
    }

    @Test
    void insertThenUpdateFailureRollsBackRepairsAndActualParentPricingChange() {
        var original = new ProductCurrencyPrice(930001, ID, Money.of(1000, KRW), null);
        tx.executeWithoutResult(s -> prices.insertAll(List.of(original), List.of()));
        var failure = new IllegalStateException("injected after price update flush");
        tx.executeWithoutResult(s -> doAnswer(call -> {
            call.callRealMethod();
            assertProjectionInTransaction(ID);
            throw failure;
        }).when(prices).updateAll(any(), any()));
        assertThatThrownBy(() -> products.updatePricing(pricing(0, 1500))).isSameAs(failure);
        assertThat(prices.findProductPrices(List.of(ID))).containsExactly(original);
        assertThat(prices.findSkuPrices(List.of(SKU, SKU + 1))).isEmpty();
        assertLegacyUnchanged();
    }

    @ParameterizedTest
    @EnumSource(value = Writer.class, names = {"ADD", "RETIRE"})
    void winningPricingRejectsStaleChildWriterWithoutOverwritingPriceRows(Writer writer) throws Exception {
        var read = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        doAnswer(call -> {
            read.countDown();
            await(committed);
            return call.callRealMethod();
        }).when(writes).claimProductVersion(ID, 0);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loser = executor.submit(() -> {
                try { mutate(writer); return "unexpected success"; }
                catch (SkuAdminException e) { return e.getErrorCode().getCode(); }
            });
            try {
                await(read);
                products.updatePricing(pricing(0, 1500));
            } finally {
                committed.countDown();
            }
            assertThat(loser.get(20, TimeUnit.SECONDS)).isEqualTo("SKU_ADMIN_002");
        }
        assertProjection(ID);
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductEntity.class, ID).getVersion()).isEqualTo(1L);
            assertThat(em.find(ProductEntity.class, ID).getSalePriceAmount()).isEqualTo(1500);
            assertThat(em.find(ProductOptionValueEntity.class, VALUE).getRetiredAt()).isNull();
            assertThat(em.createQuery("select count(s) from SkuEntity s where productId = :id", Long.class).setParameter("id", ID).getSingleResult()).isEqualTo(2);
        });
    }

    private void assertPreparationOrder(Writer writer) {
        if (writer == Writer.CREATE) {
            tx.executeWithoutResult(s -> doAnswer(call -> {
                // JDBC uses the caller's connection and cannot flush pending Hibernate changes.
                assertThat(jdbc.queryForObject("select version from product.product where id = ?", Long.class, NEW)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from product.sku where product_id = ?", Long.class, NEW)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from product.product_price where product_id = ?", Long.class, NEW)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from product.stock_adjustment_history", Long.class)).isZero();
                return call.callRealMethod();
            }).when(coordinator).prepareNew(any()));
        } else {
            tx.executeWithoutResult(s -> doAnswer(call -> {
                assertThat(jdbc.queryForObject("select version from product.product where id = ?", Long.class, ID)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select sale_price_amount from product.product where id = ?", Long.class, ID))
                    .isEqualTo(writer == Writer.PRICING ? 1500L : 1000L);
                assertThat(jdbc.queryForObject("select additional_price_amount from product.sku where id = ?", Long.class, SKU + 1)).isZero();
                assertThat(jdbc.queryForObject("select retired_at from product.product_option_value where id = ?", java.sql.Timestamp.class, VALUE)).isNull();
                assertThat(jdbc.queryForObject("select sale_status from product.sku where id = ?", String.class, SKU)).isEqualTo("ON_SALE");
                long expected = writer == Writer.ADD ? 3 : 2;
                assertThat(jdbc.queryForObject("select count(*) from product.sku where product_id = ?", Long.class, ID)).isEqualTo(expected);
                assertThat(jdbc.queryForObject("select count(*) from product.product_price where product_id = ?", Long.class, ID)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from product.stock_adjustment_history", Long.class)).isZero();
                return call.callRealMethod();
            }).when(coordinator).prepare(any(), any()));
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("transaction barrier timed out");
    }

    private void mutate(Writer writer) {
        switch (writer) {
            case CREATE -> tx.executeWithoutResult(s -> create.create(createCommand(KRW), "<p>body</p>", List.of(CATEGORY), NEW));
            case PRICING -> products.updatePricing(pricing(0, 1500));
            case ADD -> skus.add(add(0));
            case BULK -> skus.replace(bulk(0, ON_SALE, 30));
            case RETIRE -> skus.retireOptions(new OptionRetirementCommand(ID, 0, 99, Set.of(), Set.of(VALUE)));
        }
    }

    private ProductCreateCommand createCommand(CurrencyCode currency) {
        return ProductCreateCommand.builder().name("new").categoryId(CATEGORY).categoryIds(List.of()).visible(true).saleStatus(ON_SALE)
            .salePrice(Money.of(1000, currency)).optionGroups(List.of()).createdByMemberId(99)
            .skus(List.of(new ProductCreateCommand.Sku(List.of(), Money.of(0, currency), ON_SALE, 100, null,
                new ProductCreateCommand.InitialStock(true, 3)))).build();
    }

    private ProductPricingUpdateCommand pricing(long version, long amount) {
        return new ProductPricingUpdateCommand(ID, version, 99, Money.of(amount, KRW), null);
    }

    private SkuAddCommand add(long version) {
        return new SkuAddCommand(ID, version, 99, List.of(), List.of(new SkuAddCommand.NewSku(List.of("size2"), Money.of(50, KRW),
            ON_SALE, 100, null, new SkuAddCommand.InitialStock(true, 3))));
    }

    private SkuBulkUpdateCommand bulk(long version, SaleStatus first, long secondAmount) {
        return new SkuBulkUpdateCommand(ID, version, 99, List.of(
            new SkuBulkUpdateCommand.Item(SKU + 1, Money.of(secondAmount, KRW), ON_SALE, 150, null),
            new SkuBulkUpdateCommand.Item(SKU, Money.of(0, KRW), first, 100, null)));
    }

    private void additionalPrices() {
        tx.executeWithoutResult(s -> prices.insertAll(List.of(new ProductCurrencyPrice(930001, ID, Money.of(2000, USD), null)),
            List.of(new SkuCurrencyPrice(930002, SKU, Money.of(0, USD)), new SkuCurrencyPrice(930003, SKU + 1, Money.of(5, USD)))));
    }

    private void assertProjection(long productId) {
        tx.executeWithoutResult(s -> assertProjectionInTransaction(productId));
    }

    private void assertProjectionInTransaction(long productId) {
        var product = em.find(ProductEntity.class, productId);
        var base = prices.findProductPrices(List.of(productId)).stream().filter(p -> p.salePrice().currency() == product.getSalePriceCurrency()).toList();
        assertThat(base).singleElement().satisfies(p -> {
            assertThat(p.salePrice().amount()).isEqualTo(product.getSalePriceAmount());
            assertThat(p.listPrice() == null ? null : p.listPrice().amount()).isEqualTo(product.getListPriceAmount());
        });
        var legacy = em.createQuery("from SkuEntity where productId = :id order by id", SkuEntity.class).setParameter("id", productId).getResultList();
        var rows = prices.findSkuPrices(legacy.stream().map(SkuEntity::getId).toList()).stream()
            .filter(p -> p.additionalPrice().currency() == product.getSalePriceCurrency()).toList();
        assertThat(rows).hasSize(legacy.size());
        for (var sku : legacy) {
            assertThat(rows.stream().filter(p -> p.skuId() == sku.getId())).singleElement()
                .satisfies(p -> assertThat(p.additionalPrice().amount()).isEqualTo(sku.getAdditionalPriceAmount()));
        }
    }

    private void assertRolledBack() {
        assertThat(prices.findProductPrices(List.of(ID, NEW))).isEmpty();
        assertThat(prices.findSkuPrices(List.of(SKU, SKU + 1))).isEmpty();
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductEntity.class, NEW)).isNull();
            assertThat(em.createQuery("select count(p) from ProductPriceEntity p", Long.class).getSingleResult()).isZero();
            assertThat(em.createQuery("select count(p) from SkuPriceEntity p", Long.class).getSingleResult()).isZero();
            assertThat(em.createQuery("select count(h) from StockAdjustmentHistoryEntity h", Long.class).getSingleResult()).isZero();
            assertThat(em.createQuery("select count(s) from StockEntity s", Long.class).getSingleResult()).isEqualTo(1);
        });
        assertLegacyUnchanged();
    }

    private void assertLegacyUnchanged() {
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductEntity.class, ID).getVersion()).isZero();
            assertThat(em.find(ProductEntity.class, ID).getSalePriceAmount()).isEqualTo(1000);
            assertThat(em.find(ProductOptionValueEntity.class, VALUE).getRetiredAt()).isNull();
            assertThat(em.find(SkuEntity.class, SKU).getSaleStatus()).isEqualTo(ON_SALE);
            assertThat(em.find(SkuEntity.class, SKU + 1).getAdditionalPriceAmount()).isZero();
            assertThat(em.createQuery("select count(s) from SkuEntity s where s.productId = :id", Long.class).setParameter("id", ID).getSingleResult()).isEqualTo(2);
            assertThat(em.find(StockEntity.class, SKU).getAvailableQuantity()).isEqualTo(7);
        });
    }
}
