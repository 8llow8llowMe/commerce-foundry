package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockReservationItemEntity;
import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCategoryUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductHeaderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.ProductWebUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminDetailProcessor;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 가격 저장·롤백·동시 변경 계약. PostgreSQL 하위 클래스가 같은 계약을 검증한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:product-pricing-update;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductPricingUpdateIntegrationTest {
    static final long ID = 830001L;
    static final long CATEGORY = 830101L;
    static final long SKU = 830201L;
    @Autowired EntityManager em;
    @Autowired ProductAdminUseCase products;
    @Autowired ProductWebUseCase web;
    @Autowired SkuInternalUseCase internal;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ProductRepositoryPort repository;
    @MockitoSpyBean ProductAdminDetailProcessor details;
    TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            em.persist(CategoryEntity.builder().id(CATEGORY).name("category").visible(true).build());
            em.persist(ProductEntity.builder().id(ID).categoryId(CATEGORY).name("retained").summary("summary").descriptionHtml("<p>body</p>")
                .nameEn("English").summaryEn("English summary").descriptionHtmlEn("<p>English</p>")
                .visible(true).saleStatus(SaleStatus.ON_SALE).salePriceAmount(10000).salePriceCurrency(KRW)
                .listPriceAmount(20000L).listPriceCurrency(KRW).maxQuantityPerOrder(3).customOrder(true).customOrderNotice("notice")
                .customOrderLeadTimeDays(14).preorder(true).expectedShipDate(LocalDate.of(2027, 1, 1)).createdByMemberId(99L).build());
            em.persist(ProductCategoryEntity.builder().id(ID).productId(ID).categoryId(CATEGORY).displayOrder(8).build());
            em.persist(SkuEntity.builder().id(SKU).productId(ID).optionValueKey("").additionalPriceAmount(0).additionalPriceCurrency(KRW)
                .saleStatus(SaleStatus.ON_SALE).weightGrams(150).hsCode("621142").build());
            em.persist(StockEntity.builder().skuId(SKU).stockManaged(true).availableQuantity(7).build());
            em.persist(StockReservationEntity.builder().id(ID).orderId(ID).activeOrderId(ID).status(StockReservationStatus.RESERVED)
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z")).build());
            em.persist(StockReservationItemEntity.builder().id(ID).reservationId(ID).skuId(SKU).quantity(2).deducted(true).build());
        });
    }

    @AfterEach
    void cleanup() {
        reset(repository, details);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from SkuPriceEntity where skuId in (select id from SkuEntity where productId = :id)")
                .setParameter("id", ID).executeUpdate();
            em.createQuery("delete from ProductPriceEntity where productId = :id").setParameter("id", ID).executeUpdate();
            em.remove(em.find(StockReservationItemEntity.class, ID));
            em.remove(em.find(StockReservationEntity.class, ID));
            em.remove(em.find(StockEntity.class, SKU));
            em.remove(em.find(SkuEntity.class, SKU));
            em.remove(em.find(ProductCategoryEntity.class, ID));
            em.remove(em.find(ProductEntity.class, ID));
            em.remove(em.find(CategoryEntity.class, CATEGORY));
        });
    }

    @Test
    void replacesOnlyPricesAndAllReadPathsUseNewPricesWithoutChangingStockOrReservation() {
        Product before = current();
        var response = products.updatePricing(command(0, 15000, 30000L));
        assertThat(response.productId()).isEqualTo(String.valueOf(ID));
        assertThat(response.salePrice().amount()).isEqualTo(15000);
        assertThat(response.listPrice().amount()).isEqualTo(30000);
        assertThat(response.version()).isEqualTo(1L);
        assertThat(current()).usingRecursiveComparison().ignoringFields("salePrice", "listPrice", "version").isEqualTo(before);
        var detail = web.getProduct(ID);
        var list = web.searchProducts(null, ProductSortType.NEWEST, 0, 100).contents().stream()
            .filter(p -> p.productId().equals(String.valueOf(ID))).findFirst().orElseThrow();
        assertThat(detail.salePrice().amount()).isEqualTo(15000);
        assertThat(detail.skus().getFirst().salePrice().amount()).isEqualTo(15000);
        assertThat(detail.discountRate()).isEqualTo(50);
        assertThat(list.salePrice()).isEqualTo(detail.salePrice());
        assertThat(list.discountRate()).isEqualTo(detail.discountRate());
        assertThat(internal.getSkus(List.of(SKU)).skus().getFirst().salePrice()).isEqualTo(detail.salePrice());
        tx.executeWithoutResult(s -> {
            assertThat(em.find(StockEntity.class, SKU).getAvailableQuantity()).isEqualTo(7);
            assertThat(em.find(StockReservationEntity.class, ID).getStatus()).isEqualTo(StockReservationStatus.RESERVED);
            assertThat(em.find(StockReservationEntity.class, ID).getVersion()).isZero();
            assertThat(em.find(StockReservationItemEntity.class, ID).getQuantity()).isEqualTo(2);
            assertThat(em.find(SkuEntity.class, SKU).getAdditionalPriceAmount()).isZero();
            assertThat(em.find(ProductCategoryEntity.class, ID).getDisplayOrder()).isEqualTo(8);
        });
    }

    @Test
    void sameValuesKeepVersionButStaleIdenticalValuesStillConflictAndNullDeletesListPrice() {
        assertThat(products.updatePricing(command(0, 10000, 20000L)).version()).isZero();
        assertThat(products.updatePricing(command(0, 0, null)).version()).isEqualTo(1L);
        assertThat(products.updatePricing(command(1, 0, null)).version()).isEqualTo(1L);
        expectError(() -> products.updatePricing(command(0, 0, null)), ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        assertThat(current().listPrice()).isNull();
        assertThat(web.getProduct(ID).discountRate()).isNull();
    }

    @Test
    void hiddenProductsAndStoppedSkusAreEditableAndDoNotRequireZeroAdditionalPrice() {
        change("update ProductEntity set visible = false where id = :id", ID);
        change("update SkuEntity set saleStatus = 'STOP_SELLING', additionalPriceAmount = 500 where id = :id", SKU);
        assertThat(products.updatePricing(command(0, 12000, null)).salePrice().amount()).isEqualTo(12000);
        assertThat(current().visible()).isFalse();
    }

    @Test
    void invalidListPriceSkuCurrencyAndMinimumAreRejectedWithoutChanges() {
        for (Money list : List.of(Money.of(15000, KRW), Money.of(14000, KRW), Money.of(20000, USD))) {
            expectError(() -> products.updatePricing(new ProductPricingUpdateCommand(ID, 0, 99, Money.of(15000, KRW), list)),
                ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);
        }
        change("update SkuEntity set additionalPriceCurrency = 'USD' where id = :id", SKU);
        expectError(() -> products.updatePricing(command(0, 15000, null)), ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);
        change("update SkuEntity set additionalPriceCurrency = 'KRW', additionalPriceAmount = 100 where id = :id", SKU);
        expectError(() -> products.updatePricing(command(0, 15000, null)), ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);
        assertThat(current().salePrice()).isEqualTo(Money.of(10000, KRW));
        assertThat(current().version()).isZero();
    }

    @Test
    void currentCurrencyCannotChangeButExistingUsdProductCanBeEdited() {
        expectError(() -> products.updatePricing(new ProductPricingUpdateCommand(ID, 0, 99, Money.of(15000, USD), null)),
            ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);
        change("update ProductEntity set salePriceCurrency = 'USD', listPriceCurrency = 'USD' where id = :id", ID);
        change("update SkuEntity set additionalPriceCurrency = 'USD' where id = :id", SKU);
        assertThat(products.updatePricing(new ProductPricingUpdateCommand(ID, 0, 99, Money.of(15000, USD), null)).salePrice().currency()).isEqualTo("USD");
    }

    @Test
    void missingAndDeletedProductsReturnNotFoundBeforeVersionValidation() {
        expectError(() -> products.updatePricing(new ProductPricingUpdateCommand(ID + 99, 0, 99, Money.of(1, KRW), null)), ProductErrorCode.NOT_FOUND_PRODUCT);
        products.delete(ID, 99);
        expectError(() -> products.updatePricing(command(0, 15000, null)), ProductErrorCode.NOT_FOUND_PRODUCT);
    }

    @Test
    void failureAfterPriceFlushRollsBackActualDatabasePriceAndVersion() {
        doThrow(new IllegalStateException("injected read failure after flush")).when(details).getProduct(ID);
        assertThatThrownBy(() -> products.updatePricing(command(0, 15000, null))).isInstanceOf(IllegalStateException.class);
        assertThat(current().salePrice()).isEqualTo(Money.of(10000, KRW));
        assertThat(current().listPrice()).isEqualTo(Money.of(20000, KRW));
        assertThat(current().version()).isZero();
    }

    @Test
    void pricingCommittedAfterPricingReadRejectsLosingMerge() throws Exception {
        raceWithOtherWrite(() -> products.updatePricing(command(0, 17000, null)));
        assertThat(current().salePrice()).isEqualTo(Money.of(17000, KRW));
    }

    @Test
    void headerCommittedAfterPricingReadRejectsLosingMerge() throws Exception {
        raceWithOtherWrite(() -> products.updateHeader(new ProductHeaderUpdateCommand(ID, 0, 99, "winner", "new", "<p>new</p>",
            SaleStatus.STOP_SELLING, false)));
        assertThat(current().name()).isEqualTo("winner");
        assertThat(current().salePrice()).isEqualTo(Money.of(10000, KRW));
    }

    @Test
    void categoryCommittedAfterPricingReadRejectsLosingMerge() throws Exception {
        raceWithOtherWrite(() -> products.updateCategories(new ProductCategoryUpdateCommand(ID, 0, 99, CATEGORY, List.of(CATEGORY))));
        assertThat(current().salePrice()).isEqualTo(Money.of(10000, KRW));
    }

    @Test
    void deletionCommittedAfterPricingReadCannotBeOverwritten() throws Exception {
        raceWithOtherWrite(() -> products.delete(ID, 99));
        assertThat(current().isDeleted()).isTrue();
        assertThat(current().salePrice()).isEqualTo(Money.of(10000, KRW));
    }

    private void raceWithOtherWrite(Runnable other) throws Exception {
        var read = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        doAnswer(invocation -> {
            Product requested = invocation.getArgument(0);
            if (requested.salePrice().amount() == 15000) { read.countDown(); await(committed); }
            return invocation.callRealMethod();
        }).when(repository).update(any(Product.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loser = executor.submit(() -> {
                try { products.updatePricing(command(0, 15000, null)); return "success"; }
                catch (ProductException e) { return e.getErrorCode().getCode(); }
            });
            try { await(read); other.run(); } finally { committed.countDown(); }
            assertThat(loser.get(20, TimeUnit.SECONDS)).isEqualTo("PRODUCT_010");
        }
        assertThat(current().version()).isEqualTo(1L);
    }

    private void change(String jpql, long id) { tx.executeWithoutResult(s -> em.createQuery(jpql).setParameter("id", id).executeUpdate()); }
    private Product current() { return tx.execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst()); }
    private ProductPricingUpdateCommand command(long version, long sale, Long list) {
        return new ProductPricingUpdateCommand(ID, version, 99, Money.of(sale, KRW), list == null ? null : Money.of(list, KRW));
    }
    private static void expectError(Runnable action, ProductErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(error));
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("transaction barrier timed out");
    }
}
