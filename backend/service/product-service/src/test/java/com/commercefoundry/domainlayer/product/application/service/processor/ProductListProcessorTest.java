package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.info.ProductListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

@ExtendWith(MockitoExtension.class)
class ProductListProcessorTest {

    private static final ProductSearchCriteria CRITERIA = new ProductSearchCriteria(null, ProductSortType.NEWEST, 0, 20);

    @Mock
    private ProductRepositoryPort productRepositoryPort;

    @Mock
    private SkuRepositoryPort skuRepositoryPort;

    @Mock
    private ProductImageRepositoryPort productImageRepositoryPort;

    @Test
    @DisplayName("SKU 가 0건인 상품(집계 행 없음)은 판매 종료 · 기본 판매가 · 옵션 가격 차이 없음이고, hasNext 는 그대로다")
    void productWithoutSku() {
        givenRows(true, row(1, SaleStatus.ON_SALE, false));
        given(skuRepositoryPort.aggregateByProductIds(List.of(1L))).willReturn(List.of());
        given(productImageRepositoryPort.findThumbnailsByProductIds(List.of(1L))).willReturn(List.of());

        Slice<ProductListItemInfo> result = processor(true).search(CRITERIA);

        assertThat(result.hasNext()).isTrue();
        ProductListItemInfo item = result.getContent().getFirst();
        assertThat(item.saleStatus()).isEqualTo(SaleAvailability.STOP_SELLING);
        assertThat(item.salePrice()).isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
        assertThat(item.listPrice()).isEqualTo(Money.of(ProductFixtures.LIST_PRICE, KRW));
        assertThat(item.discountRate()).isEqualTo(13);
        assertThat(item.hasOptionSurcharge()).isFalse();
        assertThat(item.thumbnail()).isNull();
    }

    @Test
    @DisplayName("판매중 SKU 추가금 최소 · 최대로 표시 판매가 · 옵션 가격 차이 · 판매 상태를 계산하고, 집계 · 썸네일은 페이지의 상품 아이디로 한 번씩만 읽는다")
    void computesFromAggregates() {
        givenRows(false, row(1, SaleStatus.ON_SALE, false), row(2, SaleStatus.ON_SALE, false), row(3, SaleStatus.STOP_SELLING, false));
        given(skuRepositoryPort.aggregateByProductIds(List.of(1L, 2L, 3L))).willReturn(List.of(
            new SkuAggregateQueryResult(1, true, 1_000L, 5_000L),
            new SkuAggregateQueryResult(2, true, 0L, 0L),
            new SkuAggregateQueryResult(3, true, 0L, 0L)
        ));
        given(productImageRepositoryPort.findThumbnailsByProductIds(List.of(1L, 2L, 3L)))
            .willReturn(List.of(ProductFixtures.image(10, 1, ImageType.THUMBNAIL, 0)));

        List<ProductListItemInfo> items = processor(true).search(CRITERIA).getContent();

        assertThat(items).extracting(ProductListItemInfo::productId).containsExactly(1L, 2L, 3L);

        ProductListItemInfo surcharged = items.get(0);
        assertThat(surcharged.salePrice()).isEqualTo(Money.of(40_000, KRW));
        assertThat(surcharged.hasOptionSurcharge()).isTrue();
        assertThat(surcharged.discountRate()).isEqualTo(11);
        assertThat(surcharged.saleStatus()).isEqualTo(SaleAvailability.ON_SALE);
        assertThat(surcharged.thumbnail().imageId()).isEqualTo(10L);

        ProductListItemInfo flat = items.get(1);
        assertThat(flat.salePrice()).isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
        assertThat(flat.hasOptionSurcharge()).isFalse();
        assertThat(flat.thumbnail()).isNull();

        assertThat(items.get(2).saleStatus()).as("판매 종료 상품도 목록에 남고 뱃지로 알린다").isEqualTo(SaleAvailability.STOP_SELLING);
        verify(skuRepositoryPort, times(1)).aggregateByProductIds(anyCollection());
        verify(productImageRepositoryPort, times(1)).findThumbnailsByProductIds(anyCollection());
    }

    @Test
    @DisplayName("한 상품에 대표 이미지가 둘이면(데이터 오류) 아이디가 작은 것을 쓴다 — 실패시키지 않고 결과는 매번 같다")
    void duplicateThumbnailsPickSmallestId() {
        givenRows(false, row(1, SaleStatus.ON_SALE, false));
        given(skuRepositoryPort.aggregateByProductIds(List.of(1L))).willReturn(List.of());
        given(productImageRepositoryPort.findThumbnailsByProductIds(List.of(1L))).willReturn(List.of(
            ProductFixtures.image(20, 1, ImageType.THUMBNAIL, 0),
            ProductFixtures.image(10, 1, ImageType.THUMBNAIL, 0)
        ));

        assertThat(processor(true).search(CRITERIA).getContent().getFirst().thumbnail().imageId()).isEqualTo(10L);
    }

    @ParameterizedTest(name = "스토어 플래그 {0} · 상품 {1} → {2}")
    @CsvSource({"true, true, true", "false, true, false", "true, false, false", "false, false, false"})
    @DisplayName("customOrder 는 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 true 다")
    void customOrderNeedsFlagAndProduct(boolean flag, boolean productCustomOrder, boolean expected) {
        givenRows(false, row(1, SaleStatus.ON_SALE, productCustomOrder));
        given(skuRepositoryPort.aggregateByProductIds(anyCollection())).willReturn(List.of());
        given(productImageRepositoryPort.findThumbnailsByProductIds(anyCollection())).willReturn(List.of());

        assertThat(processor(flag).search(CRITERIA).getContent().getFirst().customOrder()).isEqualTo(expected);
    }

    @Test
    @DisplayName("목록이 비면 집계 · 썸네일 포트를 부르지 않는다")
    void emptyPageSkipsFollowUpQueries() {
        given(productRepositoryPort.searchVisible(CRITERIA)).willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

        Slice<ProductListItemInfo> result = processor(true).search(CRITERIA);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        verify(productRepositoryPort).searchVisible(CRITERIA);
        verifyNoInteractions(skuRepositoryPort, productImageRepositoryPort);
    }

    private ProductListProcessor processor(boolean customOrderFeature) {
        return new ProductListProcessor(
            productRepositoryPort, skuRepositoryPort, productImageRepositoryPort, new StoreProperties(new StoreProperties.Features(customOrderFeature))
        );
    }

    private void givenRows(boolean hasNext, ProductListQueryResult... rows) {
        given(productRepositoryPort.searchVisible(CRITERIA)).willReturn(new SliceImpl<>(List.of(rows), PageRequest.of(0, 20), hasNext));
    }

    private static ProductListQueryResult row(long productId, SaleStatus saleStatus, boolean customOrder) {
        return new ProductListQueryResult(
            productId, "상품" + productId, ProductFixtures.BASE_PRICE, KRW, ProductFixtures.LIST_PRICE, KRW, saleStatus, customOrder
        );
    }
}
