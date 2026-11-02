package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.slf4j.LoggerFactory;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.*;
import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real facade, transaction, mapper and storage. The PostgreSQL subclass also runs these contracts. */
@SpringBootTest
@ActiveProfiles("test")
class ProductDeleteIntegrationTest {
    private static final long ID = 810001L;
    @Autowired EntityManager em;
    @Autowired ProductAdminUseCase products;
    @Autowired SkuInternalUseCase skus;
    @Autowired StockInternalUseCase stocks;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ProductRepositoryPort repository;
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            em.persist(ProductEntity.builder().id(ID).categoryId(5L).name("retained").summary("summary").descriptionHtml("<p>body</p>")
                .visible(true).saleStatus(SaleStatus.ON_SALE).salePriceAmount(10000).salePriceCurrency(KRW)
                .listPriceAmount(20000L).listPriceCurrency(KRW).maxQuantityPerOrder(3).customOrder(true).customOrderNotice("notice")
                .customOrderLeadTimeDays(14).preorder(true).expectedShipDate(LocalDate.of(2027, 1, 1)).createdByMemberId(99L).build());
            em.persist(ProductCategoryEntity.builder().id(ID).productId(ID).categoryId(5L).build());
            em.persist(ProductOptionGroupEntity.builder().id(ID).productId(ID).name("color").build());
            em.persist(ProductOptionValueEntity.builder().id(ID).productId(ID).optionGroupId(ID).name("blue").build());
            em.persist(SkuEntity.builder().id(ID).productId(ID).optionValueKey(String.valueOf(ID)).additionalPriceCurrency(KRW)
                .saleStatus(SaleStatus.ON_SALE).build());
            em.persist(StockEntity.builder().skuId(ID).stockManaged(true).availableQuantity(10).build());
            em.persist(ProductImageEntity.builder().id(ID).productId(ID).imageType(ImageType.THUMBNAIL).url("https://example.com/retained.jpg").build());
            em.persist(ProductCustomOrderFieldEntity.builder().id(ID).productId(ID).fieldKey("note").label("note").inputType(CustomOrderInputType.TEXT).build());
        });
    }

    @AfterEach
    void cleanup() {
        org.mockito.Mockito.reset(repository);
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from StockReservationItemEntity where skuId = :skuId").setParameter("skuId", ID).executeUpdate();
            em.createQuery("delete from StockReservationEntity where orderId in :orders").setParameter("orders", List.of(ID, ID + 1)).executeUpdate();
            for (Class<?> type : List.of(ProductCategoryEntity.class, ProductOptionValueEntity.class, ProductOptionGroupEntity.class,
                SkuEntity.class, StockEntity.class, ProductImageEntity.class, ProductCustomOrderFieldEntity.class, ProductEntity.class)) {
                Object entity = em.find(type, ID);
                if (entity != null) em.remove(entity);
            }
        });
    }

    @Test
    void deleteRetainsHeaderChildrenAndRejectsNewPurchases() {
        Product before = tx.execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst());
        Instant created = tx.execute(s -> em.find(ProductEntity.class, ID).getCreatedAt());
        products.delete(ID, 99L);
        Product deleted = tx.execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst());
        assertThat(deleted).usingRecursiveComparison().ignoringFields("visible", "saleStatus", "deletedAt", "version").isEqualTo(before);
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(deleted.visible()).isFalse();
        assertThat(deleted.saleStatus()).isEqualTo(SaleStatus.STOP_SELLING);
        assertThat(deleted.version()).isEqualTo(before.version() + 1);
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductEntity.class, ID).getCreatedAt()).isEqualTo(created);
            for (Class<?> type : List.of(ProductCategoryEntity.class, ProductOptionValueEntity.class, ProductOptionGroupEntity.class,
                SkuEntity.class, ProductImageEntity.class, ProductCustomOrderFieldEntity.class)) {
                assertThat(em.find(type, ID)).isNotNull();
            }
            assertThat(em.find(StockEntity.class, ID).getAvailableQuantity()).isEqualTo(10);
            assertThat(repository.findVisibleById(ID)).isEmpty();
            assertThat(repository.searchVisible(new ProductSearchCriteria(null, ProductSortType.NEWEST, 0, 20)).getContent())
                .extracting(row -> row.productId()).doesNotContain(ID);
            var admin = repository.searchForAdmin(ProductAdminSearchCriteria.builder().keyword("retained")
                .sortType(ProductAdminSortType.NEWEST).page(0).size(20).build());
            assertThat(admin.getContent()).isEmpty();
            assertThat(admin.getTotalElements()).isZero();
        });
        assertThatThrownBy(() -> products.getProduct(ID)).isInstanceOf(ProductException.class);
        assertThat(skus.getSkus(List.of(ID)).skus()).singleElement().satisfies(sku -> assertThat(sku.purchasable()).isFalse());
        assertThatThrownBy(() -> stocks.reserve(new StockReservationCommand(ID, List.of(new StockReservationCommand.Item(ID, 1)))))
            .isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.STOCK_SKU_NOT_PURCHASABLE));
        Instant updated = tx.execute(s -> em.find(ProductEntity.class, ID).getUpdatedAt());
        products.delete(ID, 99L);
        assertThat(tx.<Instant>execute(s -> em.find(ProductEntity.class, ID).getUpdatedAt())).isEqualTo(updated);
        assertThat(tx.<Product>execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst())).isEqualTo(deleted);
    }

    @Test
    void existingReservationsStillConfirmAndReleaseExactlyOnce() {
        var confirmed = stocks.reserve(new StockReservationCommand(ID, List.of(new StockReservationCommand.Item(ID, 2))));
        var released = stocks.reserve(new StockReservationCommand(ID + 1, List.of(new StockReservationCommand.Item(ID, 3))));
        products.delete(ID, 99L);
        assertThat(tx.<Long>execute(status -> em.find(StockEntity.class, ID).getAvailableQuantity())).isEqualTo(5L);
        assertThat(stocks.confirm(Long.parseLong(confirmed.reservationId())).status()).isEqualTo("CONFIRMED");
        assertThat(stocks.release(Long.parseLong(released.reservationId())).status()).isEqualTo("RELEASED");
        stocks.release(Long.parseLong(released.reservationId()));
        assertThat(tx.<Long>execute(status -> em.find(StockEntity.class, ID).getAvailableQuantity())).isEqualTo(8L);
    }

    @Test
    void missingAndRollback() {
        assertThatThrownBy(() -> products.delete(ID + 1, 99L)).isInstanceOfSatisfying(ProductException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.NOT_FOUND_PRODUCT));
        tx.executeWithoutResult(s -> { products.delete(ID, 99L); s.setRollbackOnly(); });
        assertThat(tx.<Product>execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst()).isDeleted()).isFalse();
    }

    @Test
    void auditUsesActorAndCommittedOutcome() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> audit = new ListAppender<>();
        audit.start();
        logger.addAppender(audit);
        try {
            products.delete(ID, 99L);
            tx.executeWithoutResult(status -> {
                products.delete(ID, 99L);
                status.setRollbackOnly();
            });
            assertThatThrownBy(() -> products.delete(ID + 1, 99L)).isInstanceOf(ProductException.class);
            assertThat(audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList()).containsExactly(
                "action=PRODUCT_DELETE actorMemberId=99 target=product:" + ID + " result=SUCCESS",
                "action=PRODUCT_DELETE actorMemberId=99 target=product:" + ID + " result=FAILURE",
                "action=PRODUCT_DELETE actorMemberId=99 target=product:" + (ID + 1) + " result=FAILURE");
        } finally {
            logger.detachAppender(audit);
            audit.stop();
        }
    }

    @Test
    void overlappingDeletesUseLoadedVersionAndOneFailsWithConflict() throws Exception {
        CountDownLatch reads = new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            reads.countDown();
            if (!reads.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("read barrier timed out");
            return result;
        }).when(repository).findAllByIdsIncludingHidden(List.of(ID));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(this::deleteOutcome);
            var second = executor.submit(this::deleteOutcome);
            assertThat(List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("success", "PRODUCT_010");
        }
        org.mockito.Mockito.reset(repository);
        assertThat(tx.<Product>execute(s -> repository.findAllByIdsIncludingHidden(List.of(ID)).getFirst()).version()).isEqualTo(1L);
    }

    private String deleteOutcome() {
        try {
            products.delete(ID, 99L);
            return "success";
        } catch (ProductException e) {
            return e.getErrorCode().getCode();
        }
    }
}
