package com.commercefoundry.domainlayer.look.adapter.in.web.presenter;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookProductItem;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.application.service.processor.LookWebProcessor;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.presenter.ProductWebPresenter;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.properties.StoreProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 룩의 구성 상품 카드가 상품 목록 API 의 항목과 같은 값 · 같은 JSON 모양인지 — 같은 픽스처(상품 행 · SKU 집계 · 대표 이미지)를 두 경로에 넣어 직렬화 결과를 비교한다
 * ({@code DisplayProductCardParityTest} 와 같은 틀). 할인 · 옵션 추가금 · 품절 · SKU 없음 · 판매 종료 · 맞춤 제작/프리오더 플래그 · 대표 이미지 유무를 섞는다.
 */
class LookProductCardParityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ProductSearchCriteria CRITERIA = new ProductSearchCriteria(null, ProductSortType.NEWEST, 0, 20);

    @ParameterizedTest(name = "맞춤 제작 플래그 {0} · 프리오더 플래그 {1}")
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    @DisplayName("같은 상품이면 룩 상세의 products 와 상품 목록 항목의 JSON 이 같다")
    void sameCardAsProductList(boolean customOrderFeature, boolean preorderFeature) {
        List<ProductListQueryResult> rows = List.of(
            new ProductListQueryResult(1L, "할인 · 옵션 추가금", 39_000L, KRW, 45_000L, KRW, SaleStatus.ON_SALE, true, true),
            new ProductListQueryResult(2L, "품절", 20_000L, KRW, null, null, SaleStatus.ON_SALE, false, false),
            new ProductListQueryResult(3L, "SKU 없음", 10_000L, KRW, 10_000L, KRW, SaleStatus.ON_SALE, false, true),
            new ProductListQueryResult(4L, "판매 종료", 30_000L, KRW, 50_000L, KRW, SaleStatus.STOP_SELLING, true, false)
        );
        List<SkuAggregateQueryResult> aggregates = List.of(
            new SkuAggregateQueryResult(1L, true, true, 1_000L, 5_000L, 2),
            new SkuAggregateQueryResult(2L, true, false, 0L, 0L, 1),
            new SkuAggregateQueryResult(4L, true, true, 0L, 0L, 1)
        );

        ProductRepositoryPort productPort = mock(ProductRepositoryPort.class);
        SkuRepositoryPort skuPort = mock(SkuRepositoryPort.class);
        ProductImageRepositoryPort imagePort = mock(ProductImageRepositoryPort.class);
        given(productPort.searchVisible(CRITERIA)).willReturn(new SliceImpl<>(rows, PageRequest.of(0, 20), false));
        given(productPort.findVisibleListRowsByIds(anyCollection())).willReturn(rows);
        given(skuPort.aggregateByProductIds(anyCollection())).willReturn(aggregates);
        given(imagePort.findThumbnailsByProductIds(anyCollection())).willReturn(List.of(ProductFixtures.image(90L, 1L, ImageType.THUMBNAIL, 0)));
        // 상품 1 은 정지 이미지가 둘이라 hoverImage(두 번째)가 생긴다 — 양쪽 모두 non-null 로 비교된다
        given(imagePort.findStillImagesByProductIds(anyCollection())).willReturn(List.of(
            ProductFixtures.image(90L, 1L, ImageType.THUMBNAIL, 0), ProductFixtures.image(91L, 1L, ImageType.GALLERY, 1)
        ));
        StoreProperties storeProperties = new StoreProperties(new StoreProperties.Features(customOrderFeature, preorderFeature, false, true));
        ProductListProcessor productListProcessor = new ProductListProcessor(productPort, skuPort, imagePort, storeProperties);

        List<ProductListItemResponse> listed = new ProductWebPresenter().toListResponse(productListProcessor.search(CRITERIA)).contents();

        Map<Long, ProductSummary> summaries = productListProcessor.summarizeVisible(List.of(1L, 2L, 3L, 4L));
        LookContent look = new LookContent(
            new Look(7L, "룩", null, new LookImage("https://cdn.example.com/a.jpg", null, null, null), true, 0),
            List.of(item(71L, 0, 1L), item(72L, 1, 2L), item(73L, 2, 3L), item(74L, 3, 4L))
        );
        LookWebProcessor lookProcessor = new LookWebProcessor(mock(LookRepositoryPort.class), storeProperties);
        List<LookProductItem> carded = new LookWebPresenter().toDetailResponse(lookProcessor.toInfo(look, summaries)).products();

        assertThat(carded).hasSameSizeAs(listed);
        for (int index = 0; index < listed.size(); index++) {
            JsonNode expected = JSON.valueToTree(listed.get(index));
            JsonNode actual = JSON.valueToTree(carded.get(index));
            assertThat(actual).as("상품 %s", listed.get(index).productId()).isEqualTo(expected);
        }
        assertThat(carded.getFirst().preorder()).isEqualTo(preorderFeature);
        assertThat(carded.getFirst().customOrder()).isEqualTo(customOrderFeature);
        assertThat(carded.getFirst().thumbnail()).as("대표 이미지가 있는 상품").isNotNull();
        assertThat(carded.getFirst().hoverImage()).as("정지 이미지가 둘인 상품").isNotNull();
        assertThat(listed.getFirst().hoverImage()).as("상품 목록 쪽도 같은 hover 이미지").isNotNull();
        assertThat(carded.get(1).hoverImage()).as("정지 이미지가 없는 상품").isNull();
    }

    private static LookItem item(long id, int displayOrder, long productId) {
        return new LookItem(id, 7L, productId, displayOrder);
    }
}
