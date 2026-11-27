package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SkuBulkUpdatePostgresIntegrationTest extends SkuBulkUpdateIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Test
    void concurrentClaimsForTheSameBarcodeAcrossProductsCommitExactlyOnce() throws Exception {
        long secondProduct = ID + 1;
        long secondSku = SKU + 10;
        tx.executeWithoutResult(status -> {
            em.persist(ProductEntity.builder().id(secondProduct).categoryId(CATEGORY).name("other product")
                .descriptionHtml("<p>other product</p>").visible(false).saleStatus(ON_SALE)
                .salePriceAmount(10000).salePriceCurrency(KRW).createdByMemberId(99L).build());
            em.persist(SkuEntity.builder().id(secondSku).productId(secondProduct).optionValueKey("")
                .additionalPriceAmount(0).additionalPriceCurrency(KRW).saleStatus(ON_SALE).build());
        });

        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(writes).replaceEditableProperties(anyList());
        try {
            var first = barcodeCommand(0, "000-CONCURRENT", null);
            var second = new SkuBulkUpdateCommand(secondProduct, 0, 99L, List.of(
                new SkuBulkUpdateCommand.Item(secondSku, Money.of(0, KRW), ON_SALE, null, null, "000-CONCURRENT")));

            try (var executor = Executors.newFixedThreadPool(2)) {
                var firstClaim = executor.submit(() -> barcodeOutcome(first));
                var secondClaim = executor.submit(() -> barcodeOutcome(second));
                assertThat(List.of(firstClaim.get(15, TimeUnit.SECONDS), secondClaim.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "SKU_ADMIN_012");
            }

            var mapping = skuInternal.resolveBarcodes(List.of("000-CONCURRENT")).skuIdsByBarcode();
            assertThat(mapping).hasSize(1);
            boolean firstWon = mapping.get("000-CONCURRENT").equals(String.valueOf(SKU));
            tx.executeWithoutResult(status -> {
                assertThat(em.find(ProductEntity.class, ID).getVersion()).isEqualTo(firstWon ? 1L : 0L);
                assertThat(em.find(ProductEntity.class, secondProduct).getVersion()).isEqualTo(firstWon ? 0L : 1L);
                assertThat(em.find(SkuEntity.class, firstWon ? secondSku : SKU).getBarcode()).isNull();
            });
        } finally {
            tx.executeWithoutResult(status -> {
                em.createQuery("delete from SkuPriceEntity where skuId = :id").setParameter("id", secondSku).executeUpdate();
                em.createQuery("delete from ProductPriceEntity where productId = :id").setParameter("id", secondProduct).executeUpdate();
                em.remove(em.find(SkuEntity.class, secondSku));
                em.remove(em.find(ProductEntity.class, secondProduct));
            });
        }
    }

    private String barcodeOutcome(SkuBulkUpdateCommand command) {
        try {
            edits.replace(command);
            return "SUCCESS";
        } catch (SkuAdminException exception) {
            return exception.getErrorCode().getCode();
        }
    }
}
