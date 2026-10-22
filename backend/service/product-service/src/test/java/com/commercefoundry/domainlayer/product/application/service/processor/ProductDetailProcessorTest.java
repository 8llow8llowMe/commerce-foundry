package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.CategoryPathInfo;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductDetailInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductImageInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionGroupInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionValueInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductSkuInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.shared.commerce.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductDetailProcessorTest {

    private static final long PRODUCT_ID = 7L;
    // 사이즈(602) 가 색상(601) 보다 먼저 보인다. 값 아이디는 색상이 작아 조합 키("5011,9001")에서는 색상이 앞이다
    private static final long COLOR = 601L;
    private static final long SIZE = 602L;
    private static final long FABRIC = 603L;
    private static final List<Category> PATH = List.of(new Category(1, null, "상의", 0, true), new Category(11, 1L, "저고리", 0, true));

    @Mock
    private ProductRepositoryPort productRepositoryPort;

    @Mock
    private ProductOptionRepositoryPort productOptionRepositoryPort;

    @Mock
    private SkuRepositoryPort skuRepositoryPort;

    @Mock
    private StockRepositoryPort stockRepositoryPort;

    @Mock
    private ProductImageRepositoryPort productImageRepositoryPort;

    @Mock
    private ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;

    @Nested
    @DisplayName("getVisibleProduct")
    class GetVisibleProduct {

        @Test
        @DisplayName("없거나 숨긴 상품(포트가 비워 준다)이면 PRODUCT_001 이다")
        void notFound() {
            given(productRepositoryPort.findVisibleById(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> processor(true).getVisibleProduct(PRODUCT_ID))
                .isInstanceOf(ProductException.class)
                .extracting(exception -> ((ProductException) exception).getErrorCode())
                .isEqualTo(ProductErrorCode.NOT_FOUND_PRODUCT);
        }

        @Test
        @DisplayName("판매 종료 상품도 돌려준다")
        void stopSellingIsVisible() {
            Product product = ProductFixtures.product(PRODUCT_ID, SaleStatus.STOP_SELLING, true, false);
            given(productRepositoryPort.findVisibleById(PRODUCT_ID)).willReturn(Optional.of(product));

            assertThat(processor(true).getVisibleProduct(PRODUCT_ID)).isEqualTo(product);
        }
    }

    @Nested
    @DisplayName("getDetail")
    class GetDetail {

        @Test
        @DisplayName("옵션 그룹 · 값 · SKU · 이미지를 상품 단위로 읽어 화면 순서로 조립한다")
        void assemblesDetail() {
            Product product = ProductFixtures.product(PRODUCT_ID);
            givenOptions();
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.sku(1, PRODUCT_ID, "5011,9001", 0, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, PRODUCT_ID, "5012,9002", 5_000, SaleStatus.ON_SALE),
                ProductFixtures.sku(3, PRODUCT_ID, "5011,9002", 3_000, SaleStatus.STOP_SELLING)
            ));
            given(stockRepositoryPort.findAllBySkuIds(List.of(1L, 2L, 3L))).willReturn(ProductFixtures.stocks(1, 2, 3));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.image(10, PRODUCT_ID, ImageType.THUMBNAIL, 0),
                ProductFixtures.image(11, PRODUCT_ID, ImageType.GALLERY, 1),
                ProductFixtures.image(12, PRODUCT_ID, ImageType.GALLERY, 2)
            ));

            ProductDetailInfo detail = processor(true).getDetail(product, PATH);

            assertThat(detail.categoryPath()).containsExactly(new CategoryPathInfo(1, "상의"), new CategoryPathInfo(11, "저고리"));
            assertThat(detail.thumbnail().imageId()).isEqualTo(10L);
            assertThat(detail.images()).extracting(ProductImageInfo::imageId).containsExactly(11L, 12L);

            assertThat(detail.optionGroups()).extracting(ProductOptionGroupInfo::optionGroupId).containsExactly(SIZE, COLOR);
            assertThat(detail.optionGroups().get(0).values()).extracting(ProductOptionValueInfo::optionValueId).containsExactly(9001L, 9002L);
            assertThat(detail.optionGroups().get(1).values()).extracting(ProductOptionValueInfo::optionValueId).containsExactly(5011L, 5012L);

            assertThat(detail.skus()).extracting(ProductSkuInfo::skuId).containsExactly(1L, 2L, 3L);
            assertThat(detail.skus()).extracting(ProductSkuInfo::optionValueIds)
                .as("조합 키의 오름차순이 아니라 옵션 그룹 순(사이즈 → 색상)이다")
                .containsExactly(List.of(9001L, 5011L), List.of(9002L, 5012L), List.of(9002L, 5011L));
            assertThat(detail.skus().get(1).salePrice()).isEqualTo(Money.of(44_000, KRW));
            assertThat(detail.skus().get(1).additionalPrice()).isEqualTo(Money.of(5_000, KRW));
            assertThat(detail.skus().get(2).saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);

            assertThat(detail.salePrice()).as("판매중 SKU 최소 추가금 0").isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
            assertThat(detail.discountRate()).isEqualTo(13);
            assertThat(detail.maxQuantityPerOrder()).isEqualTo(10);
            assertThat(detail.customOrder()).isNull();
            verifyNoInteractions(productCustomOrderFieldRepositoryPort);
        }

        @Test
        @DisplayName("대표 이미지가 여럿이면(데이터 오류) 노출 순서 → 아이디 순으로 읽은 첫 것을 쓰고, 갤러리에는 섞지 않는다")
        void firstThumbnailWins() {
            givenNoOptionsOrSkus();
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.image(30, PRODUCT_ID, ImageType.THUMBNAIL, 0),
                ProductFixtures.image(40, PRODUCT_ID, ImageType.THUMBNAIL, 0),
                ProductFixtures.image(11, PRODUCT_ID, ImageType.GALLERY, 1)
            ));

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.thumbnail().imageId()).isEqualTo(30L);
            assertThat(detail.images()).extracting(ProductImageInfo::imageId).containsExactly(11L);
        }

        @Test
        @DisplayName("판매 종료 상품도 상세를 조립한다 — 상품 · SKU 모두 판매 종료다")
        void stopSellingProduct() {
            givenOptions();
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.sku(1, PRODUCT_ID, "5011,9001", 0, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, PRODUCT_ID, "5012,9002", 5_000, SaleStatus.ON_SALE)
            ));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID, SaleStatus.STOP_SELLING, true, false), PATH);

            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);
            assertThat(detail.skus()).extracting(ProductSkuInfo::saleStatus).containsOnly(SaleAvailability.STOP_SELLING);
            assertThat(detail.skus()).hasSize(2);
            assertThat(detail.salePrice()).isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
        }

        @Test
        @DisplayName("재고: SKU 아이디로 한 번에 읽고, 수량 0 · 재고 행 없음은 품절, 무제한은 판매중이다. 판매중 SKU 가 모두 품절이면 상품도 품절이다")
        void stockDecidesSoldOut() {
            givenOptions();
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.sku(1, PRODUCT_ID, "5011,9001", 0, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, PRODUCT_ID, "5012,9002", 5_000, SaleStatus.ON_SALE),
                ProductFixtures.sku(3, PRODUCT_ID, "5011,9002", 3_000, SaleStatus.STOP_SELLING)
            ));
            // 1 은 수량 0, 2 는 재고 행이 없다, 3 은 재고가 있지만 판매 종료다
            given(stockRepositoryPort.findAllBySkuIds(List.of(1L, 2L, 3L))).willReturn(List.of(new Stock(1, true, 0), ProductFixtures.stock(3)));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.skus()).extracting(ProductSkuInfo::saleStatus)
                .containsExactly(SaleAvailability.SOLD_OUT, SaleAvailability.SOLD_OUT, SaleAvailability.STOP_SELLING);
            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.SOLD_OUT);
            assertThat(detail.salePrice()).as("품절이어도 판매중 SKU 최소 추가금으로 계산한다").isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
            verify(stockRepositoryPort, times(1)).findAllBySkuIds(anyCollection());
        }

        @Test
        @DisplayName("재고: 무제한 SKU 는 수량과 무관하게 판매중이다")
        void unlimitedStockIsOnSale() {
            given(productOptionRepositoryPort.findGroupsByProductId(PRODUCT_ID)).willReturn(List.of());
            given(productOptionRepositoryPort.findValuesByProductId(PRODUCT_ID)).willReturn(List.of());
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(ProductFixtures.sku(1, PRODUCT_ID, "", 0, SaleStatus.ON_SALE)));
            given(stockRepositoryPort.findAllBySkuIds(List.of(1L))).willReturn(List.of(new Stock(1, false, 0)));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.skus()).extracting(ProductSkuInfo::saleStatus).containsExactly(SaleAvailability.ON_SALE);
            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
        }

        @Test
        @DisplayName("상품의 옵션 값 목록에 없는 값 아이디는 그룹 순으로 정렬한 값 뒤에 키 순서대로 붙인다")
        void unknownOptionValueGoesLast() {
            givenOptions();
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(ProductFixtures.sku(1, PRODUCT_ID, "7000,7001,9001", 0, SaleStatus.ON_SALE)));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.skus().getFirst().optionValueIds()).containsExactly(9001L, 7000L, 7001L);
        }

        @Test
        @DisplayName("은퇴: 은퇴 그룹 · 값과 은퇴 값(은퇴 그룹의 값 포함)을 쓰는 SKU 는 빠지고, 살아 있는 값만 쓰는 판매 종료 SKU 는 남는다 — 재고 · 요약도 남은 SKU 로만 본다")
        void excludesRetiredOptionsAndSkus() {
            // 소재(603) 그룹은 은퇴했고 그 값 7001 은 값 자신은 은퇴하지 않았다. 색상 값 5012 는 값만 은퇴했다
            given(productOptionRepositoryPort.findGroupsByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.group(SIZE, PRODUCT_ID, "사이즈", 1),
                ProductFixtures.group(COLOR, PRODUCT_ID, "색상", 2),
                ProductFixtures.retiredGroup(FABRIC, PRODUCT_ID, "소재", 3)
            ));
            given(productOptionRepositoryPort.findValuesByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.value(5011, COLOR, PRODUCT_ID, "쪽빛", 1),
                ProductFixtures.value(7001, FABRIC, PRODUCT_ID, "면", 1),
                ProductFixtures.value(9001, SIZE, PRODUCT_ID, "M", 1),
                ProductFixtures.retiredValue(5012, COLOR, PRODUCT_ID, "아이보리", 2),
                ProductFixtures.value(9002, SIZE, PRODUCT_ID, "L", 2)
            ));
            // 판매중 최저 추가금(0)은 은퇴 값을 쓰는 2 · 4 에만 있다 — 요약이 거르기 전 목록으로 계산하면 기본 판매가가 나온다
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.sku(1, PRODUCT_ID, "5011,9001", 5_000, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, PRODUCT_ID, "5012,9002", 0, SaleStatus.ON_SALE),
                ProductFixtures.sku(3, PRODUCT_ID, "5011,9002", 3_000, SaleStatus.STOP_SELLING),
                ProductFixtures.sku(4, PRODUCT_ID, "5011,7001,9001", 0, SaleStatus.ON_SALE)
            ));
            given(stockRepositoryPort.findAllBySkuIds(List.of(1L, 3L))).willReturn(ProductFixtures.stocks(1, 3));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.optionGroups()).extracting(ProductOptionGroupInfo::optionGroupId).containsExactly(SIZE, COLOR);
            assertThat(detail.optionGroups().get(0).values()).extracting(ProductOptionValueInfo::optionValueId).containsExactly(9001L, 9002L);
            assertThat(detail.optionGroups().get(1).values()).extracting(ProductOptionValueInfo::optionValueId).containsExactly(5011L);
            assertThat(detail.skus()).extracting(ProductSkuInfo::skuId).containsExactly(1L, 3L);
            assertThat(detail.skus()).extracting(ProductSkuInfo::saleStatus).containsExactly(SaleAvailability.ON_SALE, SaleAvailability.STOP_SELLING);
            assertThat(detail.salePrice()).as("남은 판매중 SKU(1)의 추가금 5,000 기준").isEqualTo(Money.of(ProductFixtures.BASE_PRICE + 5_000, KRW));
            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
            verify(stockRepositoryPort).findAllBySkuIds(List.of(1L, 3L));
        }

        @Test
        @DisplayName("은퇴: 판매중 SKU 가 모두 은퇴 값을 쓰면 남은 SKU 에 판매중이 없어 상품은 판매 종료다")
        void allOnSaleSkusRetired() {
            givenOptionsWithRetiredValue();
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(
                ProductFixtures.sku(1, PRODUCT_ID, "5012,9001", 0, SaleStatus.ON_SALE),
                ProductFixtures.sku(2, PRODUCT_ID, "5011,9001", 0, SaleStatus.STOP_SELLING)
            ));
            given(stockRepositoryPort.findAllBySkuIds(List.of(2L))).willReturn(ProductFixtures.stocks(2));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.skus()).extracting(ProductSkuInfo::skuId).containsExactly(2L);
            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);
        }

        @Test
        @DisplayName("SKU 가 0건이면 skus 는 비고 판매 종료 · 기본 판매가다. 이미지가 없으면 대표 이미지는 null 이다")
        void noSku() {
            given(productOptionRepositoryPort.findGroupsByProductId(PRODUCT_ID)).willReturn(List.of());
            given(productOptionRepositoryPort.findValuesByProductId(PRODUCT_ID)).willReturn(List.of());
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), List.of());

            assertThat(detail.skus()).isEmpty();
            assertThat(detail.optionGroups()).isEmpty();
            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);
            assertThat(detail.salePrice()).isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
            assertThat(detail.thumbnail()).isNull();
            assertThat(detail.images()).isEmpty();
            assertThat(detail.categoryPath()).isEmpty();
        }

        @Test
        @DisplayName("옵션이 없는 상품은 optionGroups 가 비고 optionValueIds 가 빈 SKU 하나다")
        void productWithoutOptions() {
            given(productOptionRepositoryPort.findGroupsByProductId(PRODUCT_ID)).willReturn(List.of());
            given(productOptionRepositoryPort.findValuesByProductId(PRODUCT_ID)).willReturn(List.of());
            given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of(ProductFixtures.sku(1, PRODUCT_ID, "", 0, SaleStatus.ON_SALE)));
            given(stockRepositoryPort.findAllBySkuIds(List.of(1L))).willReturn(ProductFixtures.stocks(1));
            given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.optionGroups()).isEmpty();
            assertThat(detail.skus()).singleElement().satisfies(sku -> assertThat(sku.optionValueIds()).isEmpty());
            assertThat(detail.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
        }

        @Test
        @DisplayName("스토어 플래그와 상품 설정이 모두 켜지면 맞춤 제작 입력 항목을 읽어 안내를 만든다")
        void customOrderLoadsFields() {
            givenNoOptionsSkusOrImages();
            given(productCustomOrderFieldRepositoryPort.findAllByProductId(PRODUCT_ID))
                .willReturn(List.of(ProductFixtures.numberField(1, PRODUCT_ID, "chest", 0)));

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID, SaleStatus.ON_SALE, true, true), PATH);

            assertThat(detail.customOrder().notice()).isEqualTo("제작 착수 후 취소 불가");
            assertThat(detail.customOrder().leadTimeDays()).isEqualTo(14);
            assertThat(detail.customOrder().fields()).containsExactly(CustomOrderFieldInfo.builder()
                .fieldKey("chest").label("가슴둘레").inputType(CustomOrderInputType.NUMBER).unit("cm").required(true)
                .min(new BigDecimal("60")).max(new BigDecimal("150")).step(new BigDecimal("0.5")).helpText("겨드랑이 아래")
                .build());
        }

        @Test
        @DisplayName("스토어 플래그가 꺼지면 맞춤 제작 상품이어도 입력 항목을 읽지 않고 null 이다")
        void customOrderFlagOff() {
            givenNoOptionsSkusOrImages();

            ProductDetailInfo detail = processor(false).getDetail(ProductFixtures.product(PRODUCT_ID, SaleStatus.ON_SALE, true, true), PATH);

            assertThat(detail.customOrder()).isNull();
            verifyNoInteractions(productCustomOrderFieldRepositoryPort);
        }

        @Test
        @DisplayName("맞춤 제작 상품이 아니면 플래그가 켜져 있어도 입력 항목을 읽지 않고 null 이다")
        void notCustomOrderProduct() {
            givenNoOptionsSkusOrImages();

            ProductDetailInfo detail = processor(true).getDetail(ProductFixtures.product(PRODUCT_ID), PATH);

            assertThat(detail.customOrder()).isNull();
            verifyNoInteractions(productCustomOrderFieldRepositoryPort);
        }
    }

    private ProductDetailProcessor processor(boolean customOrderFeature) {
        return new ProductDetailProcessor(
            productRepositoryPort, productOptionRepositoryPort, skuRepositoryPort, stockRepositoryPort, productImageRepositoryPort, productCustomOrderFieldRepositoryPort,
            new StoreProperties(new StoreProperties.Features(customOrderFeature))
        );
    }

    /** 포트 계약대로 노출 순서 → 아이디 오름차순으로 준다. 값은 그룹을 가리지 않고 한 줄로 정렬돼 온다. */
    private void givenOptions() {
        given(productOptionRepositoryPort.findGroupsByProductId(PRODUCT_ID)).willReturn(List.of(
            ProductFixtures.group(SIZE, PRODUCT_ID, "사이즈", 1),
            ProductFixtures.group(COLOR, PRODUCT_ID, "색상", 2)
        ));
        given(productOptionRepositoryPort.findValuesByProductId(PRODUCT_ID)).willReturn(List.of(
            ProductFixtures.value(5011, COLOR, PRODUCT_ID, "쪽빛", 1),
            ProductFixtures.value(9001, SIZE, PRODUCT_ID, "M", 1),
            ProductFixtures.value(5012, COLOR, PRODUCT_ID, "아이보리", 2),
            ProductFixtures.value(9002, SIZE, PRODUCT_ID, "L", 2)
        ));
    }

    /** 사이즈 M · 색상 쪽빛과 은퇴한 색상 값 아이보리(5012). */
    private void givenOptionsWithRetiredValue() {
        given(productOptionRepositoryPort.findGroupsByProductId(PRODUCT_ID)).willReturn(List.of(
            ProductFixtures.group(SIZE, PRODUCT_ID, "사이즈", 1),
            ProductFixtures.group(COLOR, PRODUCT_ID, "색상", 2)
        ));
        given(productOptionRepositoryPort.findValuesByProductId(PRODUCT_ID)).willReturn(List.of(
            ProductFixtures.value(5011, COLOR, PRODUCT_ID, "쪽빛", 1),
            ProductFixtures.value(9001, SIZE, PRODUCT_ID, "M", 1),
            ProductFixtures.retiredValue(5012, COLOR, PRODUCT_ID, "아이보리", 2)
        ));
    }

    private void givenNoOptionsSkusOrImages() {
        givenNoOptionsOrSkus();
        given(productImageRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());
    }

    private void givenNoOptionsOrSkus() {
        given(productOptionRepositoryPort.findGroupsByProductId(PRODUCT_ID)).willReturn(List.of());
        given(productOptionRepositoryPort.findValuesByProductId(PRODUCT_ID)).willReturn(List.of());
        given(skuRepositoryPort.findAllByProductId(PRODUCT_ID)).willReturn(List.of());
    }
}
