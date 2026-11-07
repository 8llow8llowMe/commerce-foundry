package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.STOP_SELLING;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationItemEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuAdminRepositoryPort;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminDetailProcessor;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2와 PostgreSQL이 같은 실데이터·롤백·경합 계약을 실행한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sku-bulk-update;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkuBulkUpdateIntegrationTest {
    static final long ID = 840001, CATEGORY = 840101, GROUP = 840201, SKU = 840301, VALUE = 840401;
    @Autowired EntityManager em;
    @Autowired SkuAdminUseCase edits;
    @Autowired ProductAdminUseCase products;
    @Autowired StockInternalUseCase stocks;
    @Autowired SkuRepository skuLocks;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ProductRepositoryPort productRepository;
    @MockitoSpyBean SkuAdminRepositoryPort writes;
    @MockitoSpyBean ProductAdminDetailProcessor details;
    TransactionTemplate tx;
    final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            em.persist(CategoryEntity.builder().id(CATEGORY).name("category").visible(true).build());
            em.persist(ProductEntity.builder().id(ID).categoryId(CATEGORY).name("private-name@example.invalid")
                .descriptionHtml("<p>private description</p>").visible(false).saleStatus(ON_SALE)
                .salePriceAmount(10000).salePriceCurrency(KRW).listPriceAmount(20000L).listPriceCurrency(KRW).createdByMemberId(99L).build());
            em.persist(ProductOptionGroupEntity.builder().id(GROUP).productId(ID).name("size").displayOrder(0).build());
            for (int i = 0; i < 3; i++) {
                em.persist(ProductOptionValueEntity.builder().id(VALUE + i).productId(ID).optionGroupId(GROUP).name("size" + i).displayOrder(i).build());
                em.persist(SkuEntity.builder().id(SKU + i).productId(ID).optionValueKey(String.valueOf(VALUE + i))
                    .additionalPriceAmount(i * 100).additionalPriceCurrency(KRW).saleStatus(ON_SALE).weightGrams(150).hsCode("621142").build());
            }
            em.persist(com.commercefoundry.domainlayer.skucost.adapter.out.persistence.entity.SkuCostEntity.builder()
                .id(ID).skuId(SKU).costType(com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType.GOODS)
                .amount(3000).currency(KRW).effectiveFrom(Instant.parse("2026-01-01T00:00:00Z")).reason("original cost").adminMemberId(99L).build());
            em.persist(StockEntity.builder().skuId(SKU).stockManaged(true).availableQuantity(7).build());
            em.persist(StockEntity.builder().skuId(SKU + 1).stockManaged(false).availableQuantity(0).build());
            em.persist(StockReservationEntity.builder().id(ID).orderId(ID).activeOrderId(ID).status(StockReservationStatus.RESERVED)
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z")).build());
            em.persist(StockReservationItemEntity.builder().id(ID).reservationId(ID).skuId(SKU).quantity(2).deducted(true).build());
        });
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
    }

    @AfterEach
    void cleanup() {
        auditLogger.detachAppender(audit);
        reset(productRepository, writes, details);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from SkuPriceEntity where skuId in (select id from SkuEntity where productId = :id)")
                .setParameter("id", ID).executeUpdate();
            em.createQuery("delete from ProductPriceEntity where productId = :id").setParameter("id", ID).executeUpdate();
            em.remove(em.find(com.commercefoundry.domainlayer.skucost.adapter.out.persistence.entity.SkuCostEntity.class, ID));
            em.remove(em.find(StockReservationItemEntity.class, ID));
            em.remove(em.find(StockReservationEntity.class, ID));
            em.remove(em.find(StockEntity.class, SKU));
            em.remove(em.find(StockEntity.class, SKU + 1));
            for (int i = 0; i < 3; i++) {
                em.remove(em.find(SkuEntity.class, SKU + i));
                em.remove(em.find(ProductOptionValueEntity.class, VALUE + i));
            }
            em.remove(em.find(ProductOptionGroupEntity.class, GROUP));
            em.remove(em.find(ProductEntity.class, ID));
            em.remove(em.find(CategoryEntity.class, CATEGORY));
        });
    }

    @Test
    void managedUpdatePreservesCreatedAtOptionIdentityAndOtherProductFields() {
        Product before = current();
        var created = tx.execute(s -> em.find(SkuEntity.class, SKU).getCreatedAt());
        var result = edits.replace(command(0));
        assertThat(result.productId()).isEqualTo(String.valueOf(ID));
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.skus()).hasSize(3);
        assertThat(result.skus().getFirst().saleStatus().code()).isEqualTo("STOP_SELLING");
        assertThat(result.skus().getFirst().optionValueIds()).containsExactly(String.valueOf(VALUE));
        assertThat(result.skus().getFirst().additionalPrice().currency()).isEqualTo("KRW");
        assertThat(result.skus().getFirst().weightGrams()).isNull();
        assertThat(result.skus().getFirst().hsCode()).isNull();
        assertThat(current()).usingRecursiveComparison().ignoringFields("version").isEqualTo(before);
        tx.executeWithoutResult(s -> {
            assertThat(em.find(SkuEntity.class, SKU).getCreatedAt()).isEqualTo(created);
            var cost = em.find(com.commercefoundry.domainlayer.skucost.adapter.out.persistence.entity.SkuCostEntity.class, ID);
            assertThat(cost.getAmount()).isEqualTo(3000);
            assertThat(cost.getCurrency()).isEqualTo(KRW);
            assertThat(cost.getEffectiveFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(cost.getReason()).isEqualTo("original cost");
            assertThat(em.find(SkuEntity.class, SKU).getOptionValueKey()).isEqualTo(String.valueOf(VALUE));
            assertThat(em.createQuery("select count(s) from SkuEntity s where s.productId = :id", Long.class)
                .setParameter("id", ID).getSingleResult()).isEqualTo(3);
        });
    }

    @Test
    void identicalValuesAlwaysAdvanceVersionAndStaleRetryConflicts() {
        assertThat(edits.replace(command(0)).version()).isEqualTo(1L);
        assertThat(edits.replace(command(1)).version()).isEqualTo(2L);
        expect(() -> edits.replace(command(1)), SkuAdminErrorCode.VERSION_CONFLICT);
    }

    @Test
    void duplicateMissingAdditionalOrForeignSkuIdsRollBackProductCas() {
        var original = command(0).skus();
        for (var invalid : List.of(List.of(original.getFirst(), original.getFirst(), original.getLast()), original.subList(0, 2),
            List.of(original.getFirst(), original.get(1), item(SKU + 999, 0, STOP_SELLING)))) {
            expect(() -> edits.replace(new SkuBulkUpdateCommand(ID, 0, 99, invalid)), SkuAdminErrorCode.SKU_SET_INVALID);
            assertThat(current().version()).isZero();
        }
    }

    @Test
    void currencyAndMinimumPriceRulesRollbackCasAndSkuChanges() {
        var a = item(SKU, 1, ON_SALE);
        var b = item(SKU + 1, 2, ON_SALE);
        var c = item(SKU + 2, 3, ON_SALE);
        expect(() -> edits.replace(new SkuBulkUpdateCommand(ID, 0, 99, List.of(a, b, c))), SkuAdminErrorCode.PRICE_INVALID);
        var wrongCurrency = new SkuBulkUpdateCommand.Item(SKU, Money.of(0, com.commercefoundry.shared.commerce.CurrencyCode.USD), STOP_SELLING, null, null);
        expect(() -> edits.replace(new SkuBulkUpdateCommand(ID, 0, 99, List.of(wrongCurrency, b, c))), SkuAdminErrorCode.PRICE_INVALID);
        assertThat(current().version()).isZero();
        tx.executeWithoutResult(s -> assertThat(em.find(SkuEntity.class, SKU).getWeightGrams()).isEqualTo(150));
    }

    @Test
    void retiredGroupOrValueCannotBeOnSaleButStoppedSkuCanBeEdited() {
        for (String entity : List.of("ProductOptionGroupEntity", "ProductOptionValueEntity")) {
            tx.executeWithoutResult(status -> {
                em.createQuery("update ProductOptionGroupEntity set retiredAt = null where id = :id").setParameter("id", GROUP).executeUpdate();
                em.createQuery("update ProductOptionValueEntity set retiredAt = null where id = :id").setParameter("id", VALUE).executeUpdate();
            });
            long id = entity.equals("ProductOptionGroupEntity") ? GROUP : VALUE;
            tx.executeWithoutResult(s -> em.createQuery("update " + entity + " set retiredAt = :at where id = :id")
                .setParameter("at", Instant.now()).setParameter("id", id).executeUpdate());
            var invalid = new SkuBulkUpdateCommand(ID, 0, 99,
                List.of(item(SKU, 0, ON_SALE), item(SKU + 1, 0, STOP_SELLING), item(SKU + 2, 0, STOP_SELLING)));
            expect(() -> edits.replace(invalid), SkuAdminErrorCode.RETIRED_OPTION_ON_SALE);
            assertThat(current().version()).isZero();
        }
        assertThat(edits.replace(command(0)).version()).isEqualTo(1L);
    }

    @Test
    void absentAndDeletedProductAre404BeforeCheckingVersion() {
        expect(() -> edits.replace(new SkuBulkUpdateCommand(ID + 99, 100, 99, command(0).skus())), SkuAdminErrorCode.PRODUCT_NOT_FOUND);
        products.delete(ID, 99);
        expect(() -> edits.replace(command(0)), SkuAdminErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void detailFailureAfterFlushRollsBackActualSkuAndVersionAndWritesFailureOnly() {
        doThrow(new IllegalStateException("private-name@example.invalid")).when(details).getProduct(ID);
        assertThatThrownBy(() -> edits.replace(command(0))).isInstanceOf(IllegalStateException.class);
        assertThat(current().version()).isZero();
        tx.executeWithoutResult(s -> assertThat(em.find(SkuEntity.class, SKU).getSaleStatus()).isEqualTo(ON_SALE));
        assertAudit("FAILURE");
    }

    @Test
    void managedUnlimitedMissingStockAndExistingReservationArePreservedAndReleaseIsOnceOnly() {
        edits.replace(command(0));
        tx.executeWithoutResult(s -> {
            assertThat(em.find(StockEntity.class, SKU).getAvailableQuantity()).isEqualTo(7);
            assertThat(em.find(StockEntity.class, SKU + 1).isStockManaged()).isFalse();
            assertThat(em.find(StockEntity.class, SKU + 1).getAvailableQuantity()).isZero();
            assertThat(em.find(StockEntity.class, SKU + 2)).isNull();
            assertThat(em.find(StockReservationEntity.class, ID).getStatus()).isEqualTo(StockReservationStatus.RESERVED);
            assertThat(em.find(StockReservationEntity.class, ID).getVersion()).isZero();
            assertThat(em.find(StockReservationItemEntity.class, ID).getQuantity()).isEqualTo(2);
            assertThat(em.find(StockReservationItemEntity.class, ID).isDeducted()).isTrue();
        });
        assertAudit("SUCCESS");
        stocks.release(ID);
        stocks.release(ID);
        tx.executeWithoutResult(s -> {
            assertThat(em.find(StockEntity.class, SKU).getAvailableQuantity()).isEqualTo(9);
            assertThat(em.find(StockReservationEntity.class, ID).getStatus()).isEqualTo(StockReservationStatus.RELEASED);
        });
    }

    @Test
    void simultaneousSkuEditsWithSameVersionCommitExactlyOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> { barrier.await(10, TimeUnit.SECONDS); return invocation.callRealMethod(); })
            .when(writes).claimProductVersion(ID, 0);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(this::editOutcome);
            var second = executor.submit(this::editOutcome);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("SUCCESS", "SKU_ADMIN_002");
        }
        assertThat(current().version()).isEqualTo(1L);
    }

    @Test
    void existingPricingEditReadBeforeSkuEditCannotOverwriteWinningVersion() throws Exception {
        var ready = new CountDownLatch(1);
        var edited = new CountDownLatch(1);
        doAnswer(invocation -> { ready.countDown(); await(edited); return invocation.callRealMethod(); })
            .when(productRepository).update(any(Product.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pricing = executor.submit(() -> {
                try { products.updatePricing(new ProductPricingUpdateCommand(ID, 0, 99, Money.of(12000, KRW), null)); return "SUCCESS"; }
                catch (ProductException e) { return e.getErrorCode().getCode(); }
            });
            try { await(ready); edits.replace(command(0)); } finally { edited.countDown(); }
            assertThat(pricing.get(15, TimeUnit.SECONDS)).isEqualTo("PRODUCT_010");
        }
        assertThat(current().version()).isEqualTo(1L);
        assertThat(current().salePrice().amount()).isEqualTo(10000);
    }

    @Test
    void existingPricingEditCommittedBeforeSkuCasRejectsStaleSkuWrite() throws Exception {
        var ready = new CountDownLatch(1);
        var priced = new CountDownLatch(1);
        doAnswer(invocation -> { ready.countDown(); await(priced); return invocation.callRealMethod(); }).when(writes).claimProductVersion(ID, 0);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var skuEdit = executor.submit(this::editOutcome);
            try {
                await(ready);
                products.updatePricing(new ProductPricingUpdateCommand(ID, 0, 99, Money.of(12000, KRW), null));
            } finally { priced.countDown(); }
            assertThat(skuEdit.get(15, TimeUnit.SECONDS)).isEqualTo("SKU_ADMIN_002");
        }
        assertThat(current().salePrice().amount()).isEqualTo(12000);
    }

    @Test
    void costAndStockAlertPolicySkuRowLockTimesOutAs409AndRollsBackProductCas() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(s -> {
                skuLocks.findByIdForUpdate(SKU).orElseThrow();
                locked.countDown();
                try { await(release); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }));
            try {
                await(locked);
                assertThat(executor.submit(this::editOutcome).get(12, TimeUnit.SECONDS)).isEqualTo("SKU_ADMIN_005");
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(current().version()).isZero();
        tx.executeWithoutResult(s -> assertThat(em.find(SkuEntity.class, SKU).getSaleStatus()).isEqualTo(ON_SALE));
        assertAudit("FAILURE");
    }

    @Test
    void stockRowLockDoesNotBlockSkuEditOrChangeLockedStock() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(s -> {
                em.find(StockEntity.class, SKU, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                locked.countDown();
                try { await(release); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }));
            try {
                await(locked);
                assertThat(executor.submit(this::editOutcome).get(5, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
        tx.executeWithoutResult(s -> assertThat(em.find(StockEntity.class, SKU).getAvailableQuantity()).isEqualTo(7));
    }

    @Test
    void existingReservationCanStillBeConfirmedOnceAfterSaleStops() {
        edits.replace(command(0));
        assertThat(stocks.confirm(ID).status()).isEqualTo("CONFIRMED");
        assertThat(stocks.confirm(ID).status()).isEqualTo("CONFIRMED");
        tx.executeWithoutResult(s -> {
            assertThat(em.find(StockEntity.class, SKU).getAvailableQuantity()).isEqualTo(7);
            assertThat(em.find(StockReservationEntity.class, ID).getVersion()).isEqualTo(1L);
            assertThat(em.find(StockReservationItemEntity.class, ID).getQuantity()).isEqualTo(2);
        });
    }

    private String editOutcome() {
        try { edits.replace(command(0)); return "SUCCESS"; }
        catch (SkuAdminException exception) { return exception.getErrorCode().getCode(); }
    }

    private Product current() { return tx.execute(s -> productRepository.findAllByIdsIncludingHidden(List.of(ID)).getFirst()); }
    private SkuBulkUpdateCommand command(long version) {
        return new SkuBulkUpdateCommand(ID, version, 99,
            List.of(item(SKU + 2, 900, STOP_SELLING), item(SKU + 1, 500, STOP_SELLING), item(SKU, 300, STOP_SELLING)));
    }
    private static SkuBulkUpdateCommand.Item item(long id, long amount, SaleStatus status) {
        return new SkuBulkUpdateCommand.Item(id, Money.of(amount, KRW), status, null, null);
    }
    private static void expect(Runnable run, SkuAdminErrorCode code) {
        assertThatThrownBy(run::run).isInstanceOfSatisfying(SkuAdminException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("transaction barrier timed out");
    }
    private void assertAudit(String result) {
        assertThat(audit.list.stream().map(ILoggingEvent::getFormattedMessage).filter(line -> line.startsWith("action=PRODUCT_SKUS_REPLACE")).toList())
            .containsExactly("action=PRODUCT_SKUS_REPLACE actorMemberId=99 target=product:" + ID + " result=" + result);
    }
}
