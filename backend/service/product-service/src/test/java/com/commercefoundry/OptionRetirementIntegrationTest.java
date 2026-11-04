package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.STOP_SELLING;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.application.command.OptionRetirementCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 옵션 그룹 · 값 은퇴 (#9 PR-C2) — 은퇴 값을 쓰는 판매중 SKU 는 같은 트랜잭션에서 판매 종료, 행 · 재고는 남는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:option-retirement;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OptionRetirementIntegrationTest {

    static final long ID = 895001, COLOR = 895011, SIZE = 895012, BLACK = 895021, WHITE = 895022, S = 895023, M = 895024,
        BLACK_S = 895031, BLACK_M = 895032, WHITE_S = 895033, WHITE_M = 895034;

    @Autowired EntityManager em;
    @Autowired SkuAdminUseCase skus;
    @Autowired SkuInternalUseCase internalSkus;
    @Autowired PlatformTransactionManager transactionManager;
    TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            em.persist(ProductEntity.builder().id(ID).categoryId(1L).name("저고리").visible(true).saleStatus(ON_SALE)
                .salePriceAmount(10000).salePriceCurrency(KRW).createdByMemberId(99L).build());
            em.persist(ProductOptionGroupEntity.builder().id(COLOR).productId(ID).nameEn("Color").name("색상").displayOrder(0).build());
            em.persist(ProductOptionGroupEntity.builder().id(SIZE).productId(ID).name("사이즈").displayOrder(1).build());
            em.persist(ProductOptionValueEntity.builder().id(BLACK).productId(ID).optionGroupId(COLOR).name("블랙").displayOrder(0).build());
            em.persist(ProductOptionValueEntity.builder().id(WHITE).productId(ID).optionGroupId(COLOR).nameEn("White").name("화이트").displayOrder(1).build());
            em.persist(ProductOptionValueEntity.builder().id(S).productId(ID).optionGroupId(SIZE).name("S").displayOrder(0).build());
            em.persist(ProductOptionValueEntity.builder().id(M).productId(ID).optionGroupId(SIZE).name("M").displayOrder(1).build());
            sku(BLACK_S, BLACK, S, 0);
            sku(BLACK_M, BLACK, M, 0);
            sku(WHITE_S, WHITE, S, 1000);
            sku(WHITE_M, WHITE, M, 500);
            em.persist(StockEntity.builder().skuId(WHITE_S).stockManaged(true).availableQuantity(4).build());
        });
    }

    private void sku(long id, long color, long size, long additional) {
        em.persist(SkuEntity.builder().id(id).productId(ID).optionValueKey(Math.min(color, size) + "," + Math.max(color, size))
            .additionalPriceAmount(additional).additionalPriceCurrency(KRW).saleStatus(ON_SALE).build());
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from StockEntity where skuId = :id").setParameter("id", WHITE_S).executeUpdate();
            for (var entity : List.of("SkuEntity", "ProductOptionValueEntity", "ProductOptionGroupEntity")) {
                em.createQuery("delete from " + entity + " where productId = :id").setParameter("id", ID).executeUpdate();
            }
            em.createQuery("delete from ProductEntity where id = :id").setParameter("id", ID).executeUpdate();
        });
    }

    private static OptionRetirementCommand command(long version, Set<Long> groups, Set<Long> values) {
        return new OptionRetirementCommand(ID, version, 99, groups, values);
    }

    private SaleStatus status(long skuId) {
        return tx.execute(s -> em.find(SkuEntity.class, skuId).getSaleStatus());
    }

    @Test
    @DisplayName("값을 은퇴하면 그 값을 쓰는 판매중 SKU 만 판매 종료되고, 행 · 재고는 남으며 주문이 읽는 SKU 는 구매 불가다")
    void retiresValueAndStopsItsSkus() {
        var response = skus.retireOptions(command(0, Set.of(), Set.of(WHITE)));

        assertThat(response.version()).isEqualTo(1);
        assertThat(status(WHITE_S)).isEqualTo(STOP_SELLING);
        assertThat(status(WHITE_M)).isEqualTo(STOP_SELLING);
        assertThat(status(BLACK_S)).isEqualTo(ON_SALE);
        assertThat(status(BLACK_M)).isEqualTo(ON_SALE);
        tx.executeWithoutResult(s -> {
            assertThat(em.find(ProductOptionValueEntity.class, WHITE).getRetiredAt()).isNotNull();
            assertThat(em.find(ProductOptionValueEntity.class, WHITE).getNameEn()).isEqualTo("White");
            assertThat(em.find(ProductOptionGroupEntity.class, COLOR).getNameEn()).isEqualTo("Color");
            assertThat(em.find(ProductOptionValueEntity.class, BLACK).getRetiredAt()).isNull();
            assertThat(em.find(StockEntity.class, WHITE_S).getAvailableQuantity()).isEqualTo(4);
        });
        var internal = internalSkus.getSkus(List.of(WHITE_S)).skus().getFirst();
        assertThat(internal.purchasable()).isFalse();
        assertThat(internal.optionName()).contains("화이트");
    }

    @Test
    @DisplayName("옵션(그룹)을 은퇴하면 그 옵션을 쓰는 SKU 전부가 판매 종료된다 — 판매중이 없으면 가격 규칙은 따지지 않는다")
    void retiresGroup() {
        skus.retireOptions(command(0, Set.of(SIZE), Set.of()));

        assertThat(List.of(BLACK_S, BLACK_M, WHITE_S, WHITE_M)).allSatisfy(id -> assertThat(status(id)).isEqualTo(STOP_SELLING));
        java.time.Instant retiredAt = tx.execute(s -> em.find(ProductOptionGroupEntity.class, SIZE).getRetiredAt());
        assertThat(retiredAt).isNotNull();
    }

    @Test
    @DisplayName("남는 판매중 SKU 에 추가금 0 이 없으면 400 SKU_ADMIN_004 이고 아무것도 쓰지 않는다")
    void pricingBreakWritesNothing() {
        assertError(() -> skus.retireOptions(command(0, Set.of(), Set.of(BLACK))), SkuAdminErrorCode.PRICE_INVALID);

        assertThat(status(BLACK_S)).isEqualTo(ON_SALE);
        java.time.Instant retiredAt = tx.execute(s -> em.find(ProductOptionValueEntity.class, BLACK).getRetiredAt());
        assertThat(retiredAt).isNull();
    }

    @Test
    @DisplayName("이미 은퇴한 대상만 보내면 아무것도 쓰지 않고 버전을 유지한다")
    void alreadyRetiredIsNoop() {
        skus.retireOptions(command(0, Set.of(), Set.of(WHITE)));

        assertThat(skus.retireOptions(command(1, Set.of(), Set.of(WHITE))).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 은퇴한 값과 새 값을 함께 보내면 새 값만 은퇴하고, 먼저 은퇴한 시각은 바꾸지 않는다")
    void keepsEarlierRetirementTime() {
        skus.retireOptions(command(0, Set.of(), Set.of(WHITE)));
        java.time.Instant first = tx.execute(s -> em.find(ProductOptionValueEntity.class, WHITE).getRetiredAt());

        assertThat(skus.retireOptions(command(1, Set.of(), Set.of(WHITE, M))).version()).isEqualTo(2);

        java.time.Instant after = tx.execute(s -> em.find(ProductOptionValueEntity.class, WHITE).getRetiredAt());
        java.time.Instant medium = tx.execute(s -> em.find(ProductOptionValueEntity.class, M).getRetiredAt());
        assertThat(after).isEqualTo(first);
        assertThat(medium).isNotNull();
        assertThat(status(BLACK_M)).isEqualTo(STOP_SELLING);
        assertThat(status(BLACK_S)).isEqualTo(ON_SALE);
    }

    @Test
    @DisplayName("다른 상품의 옵션은 400 SKU_ADMIN_011, 오래된 버전은 409 SKU_ADMIN_002 — 둘 다 쓰지 않는다")
    void rejectsForeignAndStale() {
        assertError(() -> skus.retireOptions(command(0, Set.of(), Set.of(WHITE, 1L))), SkuAdminErrorCode.OPTION_RETIREMENT_INVALID);
        assertError(() -> skus.retireOptions(command(5, Set.of(), Set.of(WHITE))), SkuAdminErrorCode.VERSION_CONFLICT);
        assertThat(status(WHITE_S)).isEqualTo(ON_SALE);
    }

    private static void assertError(ThrowingCallable call, SkuAdminErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SkuAdminException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
