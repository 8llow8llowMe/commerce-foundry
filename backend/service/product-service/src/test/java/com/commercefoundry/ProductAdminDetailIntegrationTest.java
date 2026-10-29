package com.commercefoundry;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.*;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.*;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 실제 H2 저장소 · 매퍼 · Facade 연결. PostgreSQL 고유 제약은 별도 Testcontainers 테스트가 검증한다. */
@SpringBootTest(properties = {
    "store.features.preorder=false", "store.features.product-video=false", "store.features.custom-order=false",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("test")
@Transactional
class ProductAdminDetailIntegrationTest {
    private static final long ID = 4_611_686_018_427_387_904L;
    private static final Instant RETIRED = Instant.parse("2026-10-20T00:00:00Z");
    @Autowired private EntityManager em;
    @Autowired private EntityManagerFactory emf;
    @Autowired private ProductAdminUseCase useCase;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ProductRepositoryPort products;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void facadeStartsReadOnlyRepeatableReadOutsideCallerTransaction() {
        long productId = ID + 1000;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> em.persist(product(productId, null)));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            return invocation.callRealMethod();
        }).when(products).findAllByIdsIncludingHidden(java.util.List.of(productId));
        try {
            assertThat(useCase.getProduct(productId).productId()).isEqualTo(String.valueOf(productId));
        } finally {
            tx.executeWithoutResult(status -> em.remove(em.find(ProductEntity.class, productId)));
        }
    }

    @Test
    void readsEditorSnapshotWithAllStorefrontFlagsDisabledAndSevenQueries() {
        em.persist(product(ID, null));
        em.persist(product(ID + 100, null));
        em.persist(ProductCategoryEntity.builder().id(1L).productId(ID).categoryId(30L).displayOrder(0).build());
        em.persist(ProductCategoryEntity.builder().id(2L).productId(ID).categoryId(20L).displayOrder(8).build());
        em.persist(ProductCategoryEntity.builder().id(3L).productId(ID + 100).categoryId(999L).build());
        em.persist(ProductOptionGroupEntity.builder().id(10L).productId(ID).name("은퇴 그룹").displayOrder(0).retiredAt(RETIRED).build());
        em.persist(ProductOptionValueEntity.builder().id(12L).productId(ID).optionGroupId(10L).name("나중 값").displayOrder(1).build());
        em.persist(ProductOptionValueEntity.builder().id(11L).productId(ID).optionGroupId(10L).name("은퇴 값").displayOrder(0).retiredAt(RETIRED).build());
        em.persist(SkuEntity.builder().id(22L).productId(ID).optionValueKey("12").additionalPriceAmount(700).additionalPriceCurrency(KRW)
            .saleStatus(SaleStatus.ON_SALE).build());
        em.persist(SkuEntity.builder().id(21L).productId(ID).optionValueKey("11").additionalPriceAmount(500).additionalPriceCurrency(KRW)
            .saleStatus(SaleStatus.STOP_SELLING).weightGrams(120).hsCode("621142").build());
        em.persist(ProductImageEntity.builder().id(31L).productId(ID).imageType(ImageType.VIDEO).url("https://example.com/v.mp4")
            .posterUrl("https://example.com/p.jpg").displayOrder(1).build());
        em.persist(ProductImageEntity.builder().id(30L).productId(ID).imageType(ImageType.THUMBNAIL).url("https://example.com/t.jpg").displayOrder(0).build());
        em.persist(ProductCustomOrderFieldEntity.builder().id(40L).productId(ID).fieldKey("memo").label("요청 사항")
            .inputType(CustomOrderInputType.TEXT).maxValue(new BigDecimal("200")).build());
        em.flush();
        Long beforeVersion = em.find(ProductEntity.class, ID).getVersion();
        em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        var response = useCase.getProduct(ID);

        assertThat(stats.getPrepareStatementCount()).isEqualTo(7);
        assertThat(response.visible()).isFalse();
        assertThat(response.categoryIds()).containsExactly("5", "20", "30");
        assertThat(response.optionGroups()).singleElement().satisfies(group -> {
            assertThat(group.retiredAt()).isEqualTo(RETIRED);
            assertThat(group.values()).extracting(value -> value.optionValueId()).containsExactly("11", "12");
            assertThat(group.values().getFirst().retiredAt()).isEqualTo(RETIRED);
        });
        assertThat(response.skus()).extracting(sku -> sku.skuId()).containsExactly("21", "22");
        assertThat(response.skus().getFirst().weightGrams()).isEqualTo(120);
        assertThat(response.skus().getFirst().hsCode()).isEqualTo("621142");
        assertThat(response.skus().getFirst().saleStatus().code()).isEqualTo("STOP_SELLING");
        assertThat(response.images()).extracting(image -> image.imageType().code()).containsExactly("THUMBNAIL", "VIDEO");
        assertThat(response.customOrderFields()).singleElement().satisfies(field -> assertThat(field.inputType().code()).isEqualTo("TEXT"));
        assertThat(response.preorder()).isTrue();
        assertThat(response.customOrder()).isTrue();
        assertThat(response.version()).isEqualTo(beforeVersion);
        em.flush();
        em.clear();
        assertThat(em.find(ProductEntity.class, ID).getVersion()).isEqualTo(beforeVersion);
    }

    @Test
    void deletedProductFailsAfterSingleQuery() {
        em.persist(product(ID, RETIRED));
        em.flush();
        em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        assertThatThrownBy(() -> useCase.getProduct(ID)).isInstanceOf(ProductException.class);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    private static ProductEntity product(long id, Instant deletedAt) {
        return ProductEntity.builder().id(id).categoryId(5L).name("숨김 상품").saleStatus(SaleStatus.STOP_SELLING).visible(false)
            .salePriceAmount(10000).salePriceCurrency(KRW).customOrder(true).customOrderNotice("저장된 안내").customOrderLeadTimeDays(14)
            .preorder(true).expectedShipDate(LocalDate.of(2026, 12, 1)).deletedAt(deletedAt).build();
    }
}
