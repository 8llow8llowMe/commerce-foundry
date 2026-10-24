package com.commercefoundry.domainlayer.display.adapter.in.web.presenter;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplayElementItem;
import com.commercefoundry.domainlayer.display.application.info.DisplayMainInfo;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
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
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메인 전시의 상품 카드가 상품 목록 API 의 항목과 같은 값 · 같은 JSON 모양인지 — 같은 픽스처(상품 행 · SKU 집계 · 대표 이미지)를 두 경로에 넣어 직렬화 결과를 비교한다.
 * 할인 · 옵션 추가금 · 품절 · SKU 없음 · 맞춤 제작 플래그 · 대표 이미지 유무를 섞는다.
 */
class DisplayProductCardParityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ProductSearchCriteria CRITERIA = new ProductSearchCriteria(null, ProductSortType.NEWEST, 0, 20);

    @Test
    @DisplayName("같은 상품이면 메인 전시 product 와 상품 목록 항목의 JSON 이 같다")
    void sameCardAsProductList() {
        List<ProductListQueryResult> rows = List.of(
            new ProductListQueryResult(1L, "할인 · 옵션 추가금", 39_000L, KRW, 45_000L, KRW, SaleStatus.ON_SALE, true),
            new ProductListQueryResult(2L, "품절", 20_000L, KRW, null, null, SaleStatus.ON_SALE, false),
            new ProductListQueryResult(3L, "SKU 없음", 10_000L, KRW, 10_000L, KRW, SaleStatus.ON_SALE, false),
            new ProductListQueryResult(4L, "판매 종료", 30_000L, KRW, 50_000L, KRW, SaleStatus.STOP_SELLING, true)
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
        ProductListProcessor productListProcessor = new ProductListProcessor(productPort, skuPort, imagePort, new StoreProperties(new StoreProperties.Features(true)));

        List<ProductListItemResponse> listed = new ProductWebPresenter().toListResponse(productListProcessor.search(CRITERIA)).contents();

        Map<Long, ProductSummary> summaries = productListProcessor.summarizeVisible(List.of(1L, 2L, 3L, 4L));
        DisplaySectionContent pick = new DisplaySectionContent(
            new DisplaySection(7L, DisplaySectionType.PRODUCT_PICK, null, null, null, null, null, 0, true, null, null),
            List.of(item(71L, 0, 1L), item(72L, 1, 2L), item(73L, 2, 3L), item(74L, 3, 4L))
        );
        DisplaySectionProcessor displayProcessor = new DisplaySectionProcessor(
            mock(DisplaySectionRepositoryPort.class), new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of(""), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );
        DisplayMainInfo info = displayProcessor.toMainInfo(List.of(pick), summaries);
        List<DisplayElementItem> displayed = new DisplayWebPresenter().toMainResponse(info).sections().getFirst().items();

        assertThat(displayed).hasSameSizeAs(listed);
        for (int index = 0; index < listed.size(); index++) {
            JsonNode expected = JSON.valueToTree(listed.get(index));
            JsonNode actual = JSON.valueToTree(displayed.get(index).product());
            assertThat(actual).as("상품 %s", listed.get(index).productId()).isEqualTo(expected);
        }
    }

    private static DisplayItem item(long id, int displayOrder, long productId) {
        return new DisplayItem(id, 7L, displayOrder, DisplayItemType.PRODUCT, null, productId, null);
    }
}
