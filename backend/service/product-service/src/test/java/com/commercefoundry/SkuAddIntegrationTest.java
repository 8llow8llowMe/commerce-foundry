package com.commercefoundry;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockAdjustmentHistoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand.InitialStock;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand.NewOptionValue;
import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand.NewSku;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.in.SkuAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
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

/** 옵션 값 · 새 조합 SKU 추가 (#9 PR-C1) — 새 행만 넣고 기존 행은 그대로, 거절이면 아무것도 쓰지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sku-add;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS product")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkuAddIntegrationTest {

    static final long ID = 890001, COLOR = 890011, SIZE = 890012, BLACK = 890021, WHITE = 890022, S = 890023, M = 890024, XL = 890025,
        BLACK_S = 890031, BLACK_M = 890032;

    @Autowired EntityManager em;
    @Autowired SkuAdminUseCase skus;
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
            em.persist(ProductOptionValueEntity.builder().id(BLACK).productId(ID).optionGroupId(COLOR).nameEn("Black").name("블랙").displayOrder(0).build());
            em.persist(ProductOptionValueEntity.builder().id(WHITE).productId(ID).optionGroupId(COLOR).name("화이트").displayOrder(1).build());
            em.persist(ProductOptionValueEntity.builder().id(S).productId(ID).optionGroupId(SIZE).name("S").displayOrder(0).build());
            em.persist(ProductOptionValueEntity.builder().id(M).productId(ID).optionGroupId(SIZE).name("M").displayOrder(1).build());
            em.persist(ProductOptionValueEntity.builder().id(XL).productId(ID).optionGroupId(SIZE).name("XL").displayOrder(2)
                .retiredAt(Instant.parse("2026-01-01T00:00:00Z")).build());
            em.persist(SkuEntity.builder().id(BLACK_S).productId(ID).optionValueKey(BLACK + "," + S).additionalPriceAmount(0)
                .additionalPriceCurrency(KRW).saleStatus(ON_SALE).build());
            em.persist(SkuEntity.builder().id(BLACK_M).productId(ID).optionValueKey(BLACK + "," + M).additionalPriceAmount(0)
                .additionalPriceCurrency(KRW).saleStatus(ON_SALE).build());
            em.persist(StockEntity.builder().skuId(BLACK_S).stockManaged(true).availableQuantity(7).build());
        });
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(s -> {
            List<Long> skuIds = em.createQuery("select e.id from SkuEntity e where e.productId = :id", Long.class).setParameter("id", ID).getResultList();
            em.createQuery("delete from StockAdjustmentHistoryEntity where skuId in :ids").setParameter("ids", skuIds).executeUpdate();
            em.createQuery("delete from StockEntity where skuId in :ids").setParameter("ids", skuIds).executeUpdate();
            for (var entity : List.of("SkuEntity", "ProductOptionValueEntity", "ProductOptionGroupEntity")) {
                em.createQuery("delete from " + entity + " where productId = :id").setParameter("id", ID).executeUpdate();
            }
            em.createQuery("delete from ProductEntity where id = :id").setParameter("id", ID).executeUpdate();
        });
    }

    private static NewSku sku(String color, String size, long additional, InitialStock stock) {
        return new NewSku(List.of(color, size), Money.of(additional, KRW), SaleStatus.ON_SALE, null, null, stock);
    }

    private static SkuAddCommand command(long version, List<NewOptionValue> values, List<NewSku> newSkus) {
        return new SkuAddCommand(ID, version, 99, values, newSkus);
    }

    private List<SkuEntity> storedSkus() {
        return tx.execute(s -> em.createQuery("from SkuEntity where productId = :id order by id", SkuEntity.class).setParameter("id", ID).getResultList());
    }

    @Test
    @DisplayName("기존 옵션에 값을 더하고 새 조합 SKU 를 만든다 — 처음 재고는 재고 행 + 입고 이력 1건, 없으면 재고 행 없음. 기존 행 · 재고는 그대로")
    void addsValuesAndSkus() {
        var stockBefore = tx.execute(s -> em.find(StockEntity.class, BLACK_S));

        var response = skus.add(command(0, List.of(new NewOptionValue(SIZE, "L")),
            List.of(sku("블랙", "L", 0, new InitialStock(true, 5)), sku("화이트", "S", 1000, null))));

        assertThat(response.version()).isEqualTo(1);
        var stored = storedSkus();
        assertThat(stored).hasSize(4);
        tx.executeWithoutResult(s -> {
            var large = em.createQuery("from ProductOptionValueEntity where optionGroupId = :g and name = 'L'", ProductOptionValueEntity.class)
                .setParameter("g", SIZE).getSingleResult();
            assertThat(large.getDisplayOrder()).isEqualTo(3);
            assertThat(large.getNameEn()).isNull();
            assertThat(em.find(ProductOptionGroupEntity.class, COLOR).getNameEn()).isEqualTo("Color");
            assertThat(em.find(ProductOptionValueEntity.class, BLACK).getNameEn()).isEqualTo("Black");
            var blackLarge = stored.stream().filter(e -> e.getOptionValueKey().equals(BLACK + "," + large.getId())).findFirst().orElseThrow();
            var whiteSmall = stored.stream().filter(e -> e.getOptionValueKey().equals(WHITE + "," + S)).findFirst().orElseThrow();
            assertThat(whiteSmall.getAdditionalPriceAmount()).isEqualTo(1000);
            var stock = em.find(StockEntity.class, blackLarge.getId());
            assertThat(stock.getAvailableQuantity()).isEqualTo(5);
            var histories = em.createQuery("from StockAdjustmentHistoryEntity where skuId = :id", StockAdjustmentHistoryEntity.class)
                .setParameter("id", blackLarge.getId()).getResultList();
            assertThat(histories).singleElement().satisfies(h -> {
                assertThat(h.getReason()).isEqualTo(StockAdjustmentReason.RESTOCK);
                assertThat(h.getAfterQuantity()).isEqualTo(5);
                assertThat(h.getAdminMemberId()).isEqualTo(99L);
            });
            assertThat(em.find(StockEntity.class, whiteSmall.getId())).isNull();
            assertThat(em.find(StockEntity.class, BLACK_S).getAvailableQuantity()).isEqualTo(stockBefore.getAvailableQuantity());
        });
    }

    @Test
    @DisplayName("이미 있는 조합은 400 SKU_ADMIN_009 이고 함께 보낸 새 값도 쓰지 않는다")
    void duplicatedCombinationWritesNothing() {
        assertError(() -> skus.add(command(0, List.of(new NewOptionValue(SIZE, "L")), List.of(sku("블랙", "S", 0, null)))),
            SkuAdminErrorCode.COMBINATION_DUPLICATED);
        assertThat(storedSkus()).hasSize(2);
        Long values = tx.execute(s -> em.createQuery("select count(e) from ProductOptionValueEntity e where e.productId = :id", Long.class)
            .setParameter("id", ID).getSingleResult());
        assertThat(values).isEqualTo(5L);
    }

    @Test
    @DisplayName("은퇴한 값의 이름은 다시 쓸 수 없고(400 SKU_ADMIN_007), 은퇴한 값은 새 조합에 고를 수 없다(400 SKU_ADMIN_008)")
    void retiredValues() {
        assertError(() -> skus.add(command(0, List.of(new NewOptionValue(SIZE, "XL")), List.of(sku("화이트", "XL", 0, null)))),
            SkuAdminErrorCode.OPTION_VALUE_INVALID);
        assertError(() -> skus.add(command(0, List.of(), List.of(sku("화이트", "XL", 0, null)))), SkuAdminErrorCode.COMBINATION_INVALID);
    }

    @Test
    @DisplayName("가격 규칙은 기존 + 새 SKU 전체로 본다 — 다른 통화는 400 SKU_ADMIN_004, 기존 0원 SKU 가 있으면 추가금 있는 SKU 도 된다")
    void pricingUsesAllSkus() {
        assertError(() -> skus.add(command(0, List.of(), List.of(new NewSku(List.of("화이트", "S"), Money.of(0, com.commercefoundry.shared.commerce.CurrencyCode.USD),
            SaleStatus.ON_SALE, null, null, null)))), SkuAdminErrorCode.PRICE_INVALID);
        assertThat(skus.add(command(0, List.of(), List.of(sku("화이트", "S", 5000, null)))).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("오래된 버전은 409 SKU_ADMIN_002 이고 쓰지 않는다 — 같은 요청을 두 번 보내도 두 번째는 409 다")
    void staleVersion() {
        skus.add(command(0, List.of(), List.of(sku("화이트", "M", 0, null))));
        assertError(() -> skus.add(command(0, List.of(), List.of(sku("화이트", "M", 0, null)))), SkuAdminErrorCode.VERSION_CONFLICT);
        assertThat(storedSkus()).hasSize(3);
    }

    @Test
    @DisplayName("더한 값을 쓰는 새 SKU 가 없으면 400 SKU_ADMIN_007 이다 — 고객 상세에 고를 수 없는 선택지가 생기지 않게")
    void unusedNewValueRejected() {
        assertError(() -> skus.add(command(0, List.of(new NewOptionValue(SIZE, "L")), List.of(sku("화이트", "S", 0, null)))),
            SkuAdminErrorCode.OPTION_VALUE_INVALID);
        assertThat(storedSkus()).hasSize(2);
    }

    @Test
    @DisplayName("은퇴한 옵션(그룹)이 있는 상품에는 새 조합을 더할 수 없다 — 400 SKU_ADMIN_008")
    void retiredGroupRejectsAdd() {
        tx.executeWithoutResult(s -> em.createQuery("update ProductOptionGroupEntity set retiredAt = :at where id = :id")
            .setParameter("at", Instant.parse("2026-01-01T00:00:00Z")).setParameter("id", SIZE).executeUpdate());

        assertError(() -> skus.add(command(0, List.of(), List.of(sku("화이트", "S", 0, null)))), SkuAdminErrorCode.COMBINATION_INVALID);
    }

    @Test
    @DisplayName("재고를 관리하지 않는(무제한) 처음 재고도 재고 행과 입고 이력이 생긴다")
    void unlimitedInitialStock() {
        skus.add(command(0, List.of(), List.of(sku("화이트", "M", 0, new InitialStock(false, 0)))));

        tx.executeWithoutResult(s -> {
            var created = em.createQuery("from SkuEntity where productId = :id and optionValueKey = :key", SkuEntity.class)
                .setParameter("id", ID).setParameter("key", WHITE + "," + M).getSingleResult();
            assertThat(em.find(StockEntity.class, created.getId()).isStockManaged()).isFalse();
            assertThat(em.createQuery("select count(h) from StockAdjustmentHistoryEntity h where h.skuId = :id", Long.class)
                .setParameter("id", created.getId()).getSingleResult()).isEqualTo(1L);
        });
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, SkuAdminErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SkuAdminException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
