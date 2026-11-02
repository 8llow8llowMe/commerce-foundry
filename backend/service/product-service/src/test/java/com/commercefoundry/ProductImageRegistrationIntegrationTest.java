package com.commercefoundry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductImageEntity;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.*;
import com.commercefoundry.shared.commerce.*;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 DB 커밋/롤백과 프록시 경계. 저장소 I/O 는 DB 트랜잭션을 잡지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
class ProductImageRegistrationIntegrationTest {
    @Autowired ProductAdminUseCase useCase;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ProductImageStoragePort storage;
    @MockitoSpyBean ProductImageRepositoryPort images;

    private ProductCreateCommand command(long category) {
        return ProductCreateCommand.builder().name("이미지 등록 검증").categoryId(category).categoryIds(List.of())
            .saleStatus(SaleStatus.ON_SALE).salePrice(Money.of(1000, CurrencyCode.KRW)).optionGroups(List.of())
            .skus(List.of(new ProductCreateCommand.Sku(List.of(), Money.of(0, CurrencyCode.KRW), SaleStatus.ON_SALE, null, null,
                new ProductCreateCommand.InitialStock(true, 3))))
            .createdByMemberId(99).images(List.of(new ProductCreateCommand.Image(
                ProductImageUploadKey.issue(99, ProductImageFormat.PNG).value(), ImageType.THUMBNAIL, "대표"))).build();
    }

    private void uploaded() {
        when(storage.attach(any(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "https://media.example.com/" + call.getArgument(1);
        });
    }

    @Test
    void commitsImageAndInitialStockTogetherAndKeepsImageAfterResponse() {
        long category = 9100101;
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> em.persist(CategoryEntity.builder().id(category).name("첨부 검증").build()));
        uploaded();
        var response = useCase.create(command(category));
        long productId = Long.parseLong(response.product().productId());
        tx.executeWithoutResult(status -> {
            var result = em.createQuery("select i from ProductImageEntity i where i.productId = :id", ProductImageEntity.class)
                .setParameter("id", productId).getSingleResult();
            assertThat(result.getStorageKey()).startsWith("products/" + productId + "/");
            assertThat(result.getWidth()).isNull();
            assertThat(response.product().images()).hasSize(1);
            assertThat(em.createQuery("select count(s) from StockEntity s where s.availableQuantity = 3", Long.class).getSingleResult()).isPositive();
        });
        verify(storage, never()).delete(anyString());
    }

    @Test
    void rollsBackProductAndChildrenBeforeDeletingOnlyCopiedTarget() {
        long category = 9100102;
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> em.persist(CategoryEntity.builder().id(category).name("롤백 검증").build()));
        uploaded();
        var failure = new IllegalStateException("image persistence failed");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(em.getEntityManagerFactory())).isFalse();
            return null;
        }).when(storage).delete(anyString());
        doThrow(failure).when(images).insertAll(anyList());
        long before = tx.execute(status -> em.createQuery("select count(p) from ProductEntity p", Long.class).getSingleResult());
        assertThatThrownBy(() -> useCase.create(command(category))).isSameAs(failure);
        verify(storage).delete(startsWith("products/"));
        long after = tx.execute(status -> em.createQuery("select count(p) from ProductEntity p", Long.class).getSingleResult());
        assertThat(after).isEqualTo(before);
    }
}
