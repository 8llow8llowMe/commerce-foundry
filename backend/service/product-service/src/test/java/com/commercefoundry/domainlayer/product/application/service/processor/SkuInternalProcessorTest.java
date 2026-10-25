package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldDefinition;
import com.commercefoundry.domainlayer.product.application.info.SkuInternalInfo;
import com.commercefoundry.domainlayer.product.application.info.SkuOptionValueInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.shared.commerce.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkuInternalProcessorTest {

    private static final long PRODUCT_ID = 7L;
    // 사이즈(602) 가 색상(601) 보다 먼저 보인다. 조합 키("5011,9001")에서는 값 아이디가 작은 색상이 앞이다
    private static final long COLOR = 601L;
    private static final long SIZE = 602L;

    @Mock
    private SkuRepositoryPort skuRepositoryPort;

    @Mock
    private ProductRepositoryPort productRepositoryPort;

    @Mock
    private StockRepositoryPort stockRepositoryPort;

    @Mock
    private ProductOptionRepositoryPort productOptionRepositoryPort;

    @Mock
    private ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;

    @Test
    @DisplayName("중복은 첫 등장 순서로 한 번만 조회하고, 응답은 요청 순서(중복 제거 후)다 — 포트가 돌려준 순서와 무관하다")
    void deduplicatesAndKeepsRequestOrder() {
        given(skuRepositoryPort.findAllByIds(List.of(3L, 1L, 2L))).willReturn(List.of(noOptionSku(1), noOptionSku(2), noOptionSku(3)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID))).willReturn(List.of(ProductFixtures.product(PRODUCT_ID)));
        givenNoOptions();

        List<SkuInternalInfo> skus = processor(true).getSkus(List.of(3L, 1L, 3L, 2L));

        assertThat(skus).extracting(SkuInternalInfo::skuId).containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("없는 아이디는 응답에서 뺀다 — 호출한 쪽이 누락을 보고 거절한다")
    void omitsUnknownIds() {
        given(skuRepositoryPort.findAllByIds(List.of(1L, 99L))).willReturn(List.of(noOptionSku(1)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID))).willReturn(List.of(ProductFixtures.product(PRODUCT_ID)));
        givenNoOptions();

        assertThat(processor(true).getSkus(List.of(1L, 99L))).extracting(SkuInternalInfo::skuId).containsExactly(1L);
    }

    @Test
    @DisplayName("SKU 가 하나도 없으면 상품 · 재고 · 옵션 포트를 부르지 않고 빈 목록이다")
    void noSkuSkipsFollowUpQueries() {
        given(skuRepositoryPort.findAllByIds(List.of(99L))).willReturn(List.of());

        assertThat(processor(true).getSkus(List.of(99L))).isEmpty();
        verifyNoInteractions(productRepositoryPort, stockRepositoryPort, productOptionRepositoryPort, productCustomOrderFieldRepositoryPort);
    }

    @Test
    @DisplayName("상품이 없는 SKU(데이터 오류)는 응답에서 뺀다")
    void omitsSkuWithoutProduct() {
        given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(noOptionSku(1)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID))).willReturn(List.of());
        givenNoOptions();

        assertThat(processor(true).getSkus(List.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("상품이 없는 SKU 의 재고 · 옵션 값은 읽지 않는다 — 어차피 응답에서 빠진다")
    void skipsOptionValuesOfSkuWithoutProduct() {
        given(skuRepositoryPort.findAllByIds(List.of(1L, 2L))).willReturn(List.of(
            ProductFixtures.sku(1, PRODUCT_ID, "5011", 0, SaleStatus.ON_SALE),
            ProductFixtures.sku(2, 8L, "7000", 0, SaleStatus.ON_SALE)
        ));
        // 상품 8 이 없다
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID, 8L))).willReturn(List.of(ProductFixtures.product(PRODUCT_ID)));
        given(productOptionRepositoryPort.findValuesByIds(Set.of(5011L))).willReturn(List.of(ProductFixtures.value(5011, COLOR, PRODUCT_ID, "쪽빛", 1)));
        given(productOptionRepositoryPort.findGroupsByIds(Set.of(COLOR))).willReturn(List.of(ProductFixtures.group(COLOR, PRODUCT_ID, "색상", 1)));

        List<SkuInternalInfo> skus = processor(true).getSkus(List.of(1L, 2L));

        assertThat(skus).extracting(SkuInternalInfo::skuId).containsExactly(1L);
        assertThat(skus.getFirst().optionName()).isEqualTo("쪽빛");
        verify(stockRepositoryPort).findAllBySkuIds(List.of(1L));
        verify(productOptionRepositoryPort).findValuesByIds(Set.of(5011L));
    }

    @Test
    @DisplayName("판매가 = 기본 판매가 + 추가금이고 상품 정보(이름 · 1회 최대 수량)를 싣는다")
    void pricesAndProductFields() {
        given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(ProductFixtures.sku(1, PRODUCT_ID, "", 5_000, SaleStatus.ON_SALE)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID))).willReturn(List.of(ProductFixtures.product(PRODUCT_ID)));
        given(stockRepositoryPort.findAllBySkuIds(List.of(1L))).willReturn(ProductFixtures.stocks(1));
        givenNoOptions();

        SkuInternalInfo sku = processor(true).getSkus(List.of(1L)).getFirst();

        assertThat(sku.productId()).isEqualTo(PRODUCT_ID);
        assertThat(sku.productName()).isEqualTo("상품7");
        assertThat(sku.salePrice()).isEqualTo(Money.of(44_000, KRW));
        assertThat(sku.additionalPrice()).isEqualTo(Money.of(5_000, KRW));
        assertThat(sku.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
        assertThat(sku.purchasable()).isTrue();
        assertThat(sku.maxQuantityPerOrder()).isEqualTo(10);
    }

    @Test
    @DisplayName("SKU 무게 · HS 코드를 싣고, 미입력이면 null 을 그대로 내린다 — 해외 주문 거절은 호출한 쪽이 한다")
    void weightAndHsCode() {
        given(skuRepositoryPort.findAllByIds(List.of(1L, 2L))).willReturn(List.of(
            ProductFixtures.sku(1, PRODUCT_ID, "", 0, SaleStatus.ON_SALE, 450, "6211420000"),
            noOptionSku(2)
        ));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID))).willReturn(List.of(ProductFixtures.product(PRODUCT_ID)));
        givenNoOptions();

        List<SkuInternalInfo> skus = processor(true).getSkus(List.of(1L, 2L));

        assertThat(skus).extracting(SkuInternalInfo::skuId, SkuInternalInfo::weightGrams, SkuInternalInfo::hsCode)
            .containsExactly(tuple(1L, 450, new HsCode("6211420000")), tuple(2L, null, null));
    }

    /**
     * {@code purchasable = 상품 노출 && saleStatus == ON_SALE}. 숨긴 상품의 SKU 도 행은 내리고(판매 상태는 판매 상태대로), 구매만 막는다.
     * 판매 상태는 실제 재고에서 낸다 — 재고 행이 없는 SKU 는 품절이다. 판매 종료가 품절보다 앞선다.
     */
    @ParameterizedTest(name = "[{0}] 노출 {1} · 상품 {2} · SKU {3} · 재고 {4} → {5} · purchasable={6}")
    @CsvSource({
        "판매중 상품의 판매중 SKU,   true,  ON_SALE,      ON_SALE,      IN_STOCK,  ON_SALE,      true",
        "숨긴 상품의 SKU,            false, ON_SALE,      ON_SALE,      IN_STOCK,  ON_SALE,      false",
        "판매 종료 상품의 SKU,       true,  STOP_SELLING, ON_SALE,      IN_STOCK,  STOP_SELLING, false",
        "판매중이 아닌 SKU,          true,  ON_SALE,      STOP_SELLING, IN_STOCK,  STOP_SELLING, false",
        "수량 0 인 SKU,              true,  ON_SALE,      ON_SALE,      ZERO,      SOLD_OUT,     false",
        "재고 행이 없는 SKU,         true,  ON_SALE,      ON_SALE,      NONE,      SOLD_OUT,     false",
        "무제한 SKU,                 true,  ON_SALE,      ON_SALE,      UNLIMITED, ON_SALE,      true",
        "판매 종료 SKU 의 품절,      true,  ON_SALE,      STOP_SELLING, ZERO,      STOP_SELLING, false"
    })
    @DisplayName("purchasable 은 상품이 노출 중이고 판매 상태(재고 반영)가 ON_SALE 일 때만 true 다")
    void purchasable(
        String caseName, boolean visible, SaleStatus productStatus, SaleStatus skuStatus, String stockCase,
        SaleAvailability expectedSaleStatus, boolean expectedPurchasable
    ) {
        given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(ProductFixtures.sku(1, PRODUCT_ID, "", 0, skuStatus)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID)))
            .willReturn(List.of(ProductFixtures.product(PRODUCT_ID, productStatus, visible, false)));
        given(stockRepositoryPort.findAllBySkuIds(List.of(1L))).willReturn(switch (stockCase) {
            case "IN_STOCK" -> ProductFixtures.stocks(1);
            case "ZERO" -> List.of(new Stock(1, true, 0));
            case "UNLIMITED" -> List.of(new Stock(1, false, 0));
            case "NONE" -> List.of();
            default -> throw new IllegalArgumentException(stockCase);
        });
        givenNoOptions();

        SkuInternalInfo sku = processor(true).getSkus(List.of(1L)).getFirst();

        assertThat(sku.skuId()).as(caseName + " — 행은 내린다").isEqualTo(1L);
        assertThat(sku.saleStatus()).as(caseName).isEqualTo(expectedSaleStatus);
        assertThat(sku.saleStatus().toMetadata().code()).as(caseName).isEqualTo(expectedSaleStatus.name());
        assertThat(sku.purchasable()).as(caseName).isEqualTo(expectedPurchasable);
    }

    @Test
    @DisplayName("옵션은 그룹 (노출 순서, 아이디) 순으로 놓고 optionName 은 값 이름을 ' / ' 로 잇는다. 옵션이 없으면 빈 목록 · 빈 문자열이다")
    void optionNamesInGroupOrder() {
        given(skuRepositoryPort.findAllByIds(List.of(1L, 2L))).willReturn(List.of(
            ProductFixtures.sku(1, PRODUCT_ID, "5011,9001", 0, SaleStatus.ON_SALE),
            noOptionSku(2)
        ));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID))).willReturn(List.of(ProductFixtures.product(PRODUCT_ID)));
        given(productOptionRepositoryPort.findValuesByIds(Set.of(5011L, 9001L))).willReturn(List.of(
            ProductFixtures.value(9001, SIZE, PRODUCT_ID, "M", 1),
            ProductFixtures.value(5011, COLOR, PRODUCT_ID, "쪽빛", 1)
        ));
        given(productOptionRepositoryPort.findGroupsByIds(Set.of(SIZE, COLOR))).willReturn(List.of(
            ProductFixtures.group(COLOR, PRODUCT_ID, "색상", 2),
            ProductFixtures.group(SIZE, PRODUCT_ID, "사이즈", 1)
        ));

        List<SkuInternalInfo> skus = processor(true).getSkus(List.of(1L, 2L));

        assertThat(skus.get(0).optionValues()).containsExactly(new SkuOptionValueInfo("사이즈", "M"), new SkuOptionValueInfo("색상", "쪽빛"));
        assertThat(skus.get(0).optionName()).isEqualTo("M / 쪽빛");
        assertThat(skus.get(1).optionValues()).isEmpty();
        assertThat(skus.get(1).optionName()).isEmpty();
    }

    @Test
    @DisplayName("옵션 값이나 그룹이 없으면(데이터 오류) 그 칸만 빼고 나머지는 낸다")
    void skipsMissingValueOrGroup() {
        given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(ProductFixtures.sku(1, PRODUCT_ID, "5011,7000,9001", 0, SaleStatus.ON_SALE)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID))).willReturn(List.of(ProductFixtures.product(PRODUCT_ID)));
        // 7000 은 값이 없고, 5011 은 그룹(601)이 없다
        given(productOptionRepositoryPort.findValuesByIds(Set.of(5011L, 7000L, 9001L))).willReturn(List.of(
            ProductFixtures.value(5011, COLOR, PRODUCT_ID, "쪽빛", 1),
            ProductFixtures.value(9001, SIZE, PRODUCT_ID, "M", 1)
        ));
        given(productOptionRepositoryPort.findGroupsByIds(Set.of(COLOR, SIZE))).willReturn(List.of(ProductFixtures.group(SIZE, PRODUCT_ID, "사이즈", 1)));

        SkuInternalInfo sku = processor(true).getSkus(List.of(1L)).getFirst();

        assertThat(sku.optionValues()).containsExactly(new SkuOptionValueInfo("사이즈", "M"));
        assertThat(sku.optionName()).isEqualTo("M");
        assertThat(sku.salePrice()).isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
    }

    @ParameterizedTest(name = "스토어 플래그 {0} · 상품 {1} → {2}")
    @CsvSource({"true, true, true", "false, true, false", "true, false, false"})
    @DisplayName("customOrder 는 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 true 다")
    void customOrderNeedsFlagAndProduct(boolean flag, boolean productCustomOrder, boolean expected) {
        given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(noOptionSku(1)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID)))
            .willReturn(List.of(ProductFixtures.product(PRODUCT_ID, SaleStatus.ON_SALE, true, productCustomOrder)));
        givenNoOptions();

        assertThat(processor(flag).getSkus(List.of(1L)).getFirst().customOrder()).isEqualTo(expected);
    }

    @Test
    @DisplayName("스토어 맞춤 제작이 꺼져 있으면 항목 정의를 읽지 않는다")
    void customOrderFlagOffSkipsFieldQuery() {
        given(skuRepositoryPort.findAllByIds(List.of(1L))).willReturn(List.of(noOptionSku(1)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID)))
            .willReturn(List.of(ProductFixtures.product(PRODUCT_ID, SaleStatus.ON_SALE, true, true)));
        givenNoOptions();

        SkuInternalInfo sku = processor(false).getSkus(List.of(1L)).getFirst();

        assertThat(sku.customOrder()).isFalse();
        assertThat(sku.customOrderFields()).isEmpty();
        verify(productCustomOrderFieldRepositoryPort, never()).findAllByProductIds(anyCollection());
    }

    @Test
    @DisplayName("맞춤 제작 항목 정의는 상품 아이디로 한 번만 읽고, 맞춤 제작이 아닌 SKU 는 빈 목록이다")
    void customOrderFieldsOncePerPage() {
        given(skuRepositoryPort.findAllByIds(List.of(1L, 2L))).willReturn(List.of(noOptionSku(1), ProductFixtures.sku(2, 8L, "", 0, SaleStatus.ON_SALE)));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID, 8L))).willReturn(List.of(
            ProductFixtures.product(PRODUCT_ID, SaleStatus.ON_SALE, true, true),
            ProductFixtures.product(8L)
        ));
        given(productCustomOrderFieldRepositoryPort.findAllByProductIds(Set.of(PRODUCT_ID))).willReturn(List.of(
            ProductFixtures.numberField(10, PRODUCT_ID, "waist", 0),
            ProductFixtures.numberField(20, PRODUCT_ID, "chest", 1)
        ));
        givenNoOptions();

        List<SkuInternalInfo> skus = processor(true).getSkus(List.of(1L, 2L));

        assertThat(skus.get(0).customOrder()).isTrue();
        assertThat(skus.get(0).customOrderFields()).containsExactly(
            new CustomOrderFieldDefinition("waist", CustomOrderInputType.NUMBER, true, new BigDecimal("60"), new BigDecimal("150"), new BigDecimal("0.5")),
            new CustomOrderFieldDefinition("chest", CustomOrderInputType.NUMBER, true, new BigDecimal("60"), new BigDecimal("150"), new BigDecimal("0.5"))
        );
        assertThat(skus.get(1).customOrderFields()).isEmpty();
        verify(productCustomOrderFieldRepositoryPort).findAllByProductIds(Set.of(PRODUCT_ID));
        verify(productCustomOrderFieldRepositoryPort, never()).findAllByProductId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("상품 · 재고 · 옵션 값 · 옵션 그룹은 SKU 들의 아이디로 한 번씩만 읽는다 (N+1 금지)")
    void bulkQueriesOnce() {
        given(skuRepositoryPort.findAllByIds(List.of(1L, 2L))).willReturn(List.of(
            ProductFixtures.sku(1, PRODUCT_ID, "5011", 0, SaleStatus.ON_SALE),
            ProductFixtures.sku(2, 8L, "5011", 0, SaleStatus.ON_SALE)
        ));
        given(productRepositoryPort.findAllByIdsIncludingHidden(Set.of(PRODUCT_ID, 8L)))
            .willReturn(List.of(ProductFixtures.product(PRODUCT_ID), ProductFixtures.product(8L)));
        given(productOptionRepositoryPort.findValuesByIds(Set.of(5011L))).willReturn(List.of(ProductFixtures.value(5011, COLOR, PRODUCT_ID, "쪽빛", 1)));
        given(productOptionRepositoryPort.findGroupsByIds(Set.of(COLOR))).willReturn(List.of(ProductFixtures.group(COLOR, PRODUCT_ID, "색상", 1)));

        assertThat(processor(true).getSkus(List.of(1L, 2L))).hasSize(2);
        verify(productRepositoryPort).findAllByIdsIncludingHidden(Set.of(PRODUCT_ID, 8L));
        verify(stockRepositoryPort).findAllBySkuIds(List.of(1L, 2L));
        verify(productOptionRepositoryPort).findValuesByIds(Set.of(5011L));
        verify(productOptionRepositoryPort).findGroupsByIds(Set.of(COLOR));
    }

    private SkuInternalProcessor processor(boolean customOrderFeature) {
        return new SkuInternalProcessor(
            skuRepositoryPort, productRepositoryPort, stockRepositoryPort, productOptionRepositoryPort,
            productCustomOrderFieldRepositoryPort, new StoreProperties(new StoreProperties.Features(customOrderFeature, false, false))
        );
    }

    private static Sku noOptionSku(long skuId) {
        return ProductFixtures.sku(skuId, PRODUCT_ID, "", 0, SaleStatus.ON_SALE);
    }

    /** 옵션이 없는 SKU 만 있으면 값 아이디가 비어 포트는 빈 컬렉션을 받는다 (어댑터가 쿼리 없이 빈 목록). */
    private void givenNoOptions() {
        given(productOptionRepositoryPort.findValuesByIds(anyCollection())).willReturn(List.of());
        given(productOptionRepositoryPort.findGroupsByIds(anyCollection())).willReturn(List.of());
    }
}
