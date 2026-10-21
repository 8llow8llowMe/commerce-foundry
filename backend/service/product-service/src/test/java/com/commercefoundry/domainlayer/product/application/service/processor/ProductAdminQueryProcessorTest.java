package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductAvailabilityFilter;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class ProductAdminQueryProcessorTest {

    private static final ProductAdminSearchCommand ALL = ProductAdminSearchCommand.builder().sortType(ProductAdminSortType.NEWEST).page(0).size(20).build();
    private static final Instant UPDATED_AT = Instant.parse("2026-10-18T09:00:00Z");

    @Mock
    private ProductRepositoryPort productRepositoryPort;

    @Mock
    private SkuRepositoryPort skuRepositoryPort;

    @Mock
    private ProductImageRepositoryPort productImageRepositoryPort;

    @Test
    @DisplayName("요청 값을 조회 조건으로 바꾼다 — 펼친 카테고리 집합 · domain enum · 앞뒤 공백을 뺀 검색어 · 정렬 · 페이지")
    void commandToCriteria() {
        givenEmptyPage(2, 50);
        ProductAdminSearchCommand command = ProductAdminSearchCommand.builder()
            .keyword("  저고리 ")
            .categoryId(10L)
            .saleStatus(ProductSaleStatusFilter.STOP_SELLING)
            .visible(false)
            .availability(ProductAvailabilityFilter.SOLD_OUT)
            .sortType(ProductAdminSortType.NAME_ASC)
            .page(2)
            .size(50)
            .build();

        processor().search(command, Set.of(10L, 11L, 12L));

        assertThat(capturedCriteria()).isEqualTo(new ProductAdminSearchCriteria(
            "저고리", Set.of(10L, 11L, 12L), SaleStatus.STOP_SELLING, false, SaleAvailability.SOLD_OUT, ProductAdminSortType.NAME_ASC, 2, 50
        ));
    }

    @Test
    @DisplayName("조건이 없으면 모두 null 이다 — 숨김 상품까지 전체이고, 공백뿐인 검색어는 조건에서 빠진다")
    void noFilters() {
        givenEmptyPage(0, 20);
        ProductAdminSearchCommand command = ProductAdminSearchCommand.builder().keyword("   ").sortType(ProductAdminSortType.NEWEST).page(0).size(20).build();

        processor().search(command, null);

        assertThat(capturedCriteria()).isEqualTo(new ProductAdminSearchCriteria(null, null, null, null, null, ProductAdminSortType.NEWEST, 0, 20));
    }

    @ParameterizedTest
    @EnumSource(ProductSaleStatusFilter.class)
    @DisplayName("판매 상태 입력 enum 은 같은 이름의 SaleStatus 로 바뀌고, 값 집합이 domain 과 같다")
    void saleStatusFilterMatchesDomain(ProductSaleStatusFilter filter) {
        assertThat(filter.toSaleStatus().name()).isEqualTo(filter.name());
        assertThat(Arrays.stream(ProductSaleStatusFilter.values()).map(Enum::name))
            .containsExactlyElementsOf(Arrays.stream(SaleStatus.values()).map(Enum::name).toList());
    }

    @ParameterizedTest
    @EnumSource(ProductAvailabilityFilter.class)
    @DisplayName("판매 가능 상태 입력 enum 은 같은 이름의 SaleAvailability 로 바뀌고, 값 집합이 domain 과 같다")
    void availabilityFilterMatchesDomain(ProductAvailabilityFilter filter) {
        assertThat(filter.toSaleAvailability().name()).isEqualTo(filter.name());
        assertThat(Arrays.stream(ProductAvailabilityFilter.values()).map(Enum::name))
            .containsExactlyElementsOf(Arrays.stream(SaleAvailability.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("행을 항목으로 바꾼다 — 저장된 가격 · 판매 상태 · 노출 그대로, 판매 가능 상태는 SKU 집계로, 대표 이미지 주소 · SKU 수 · 수정 시각을 싣는다")
    void mapsRows() {
        givenRows(
            row(1, SaleStatus.ON_SALE, true, ProductFixtures.LIST_PRICE),
            row(2, SaleStatus.ON_SALE, false, null),
            row(3, SaleStatus.STOP_SELLING, true, null),
            row(4, SaleStatus.ON_SALE, true, null)
        );
        given(skuRepositoryPort.aggregateByProductIds(List.of(1L, 2L, 3L, 4L))).willReturn(List.of(
            new SkuAggregateQueryResult(1, true, true, 1_000L, 5_000L, 3),
            new SkuAggregateQueryResult(2, true, false, 0L, 0L, 2),
            new SkuAggregateQueryResult(3, true, true, 0L, 0L, 1)
        ));
        given(productImageRepositoryPort.findThumbnailsByProductIds(List.of(1L, 2L, 3L, 4L))).willReturn(List.of(
            ProductFixtures.image(20, 1, ImageType.THUMBNAIL, 0),
            ProductFixtures.image(10, 1, ImageType.THUMBNAIL, 0)
        ));

        Page<ProductAdminListItemInfo> page = processor().search(ALL, null);

        assertThat(page.getTotalElements()).isEqualTo(4);
        List<ProductAdminListItemInfo> items = page.getContent();
        ProductAdminListItemInfo first = items.getFirst();
        assertThat(first.productId()).isEqualTo(1L);
        assertThat(first.name()).isEqualTo("상품1");
        assertThat(first.salePrice()).as("옵션 추가금을 더하지 않은 기본 판매가").isEqualTo(Money.of(ProductFixtures.BASE_PRICE, KRW));
        assertThat(first.listPrice()).isEqualTo(Money.of(ProductFixtures.LIST_PRICE, KRW));
        assertThat(first.saleStatus()).isEqualTo(SaleStatus.ON_SALE);
        assertThat(first.visible()).isTrue();
        assertThat(first.availability()).isEqualTo(SaleAvailability.ON_SALE);
        assertThat(first.skuCount()).isEqualTo(3);
        assertThat(first.thumbnailUrl()).as("대표 이미지가 둘이면 아이디가 작은 쪽").isEqualTo(ProductFixtures.image(10, 1, ImageType.THUMBNAIL, 0).url());
        assertThat(first.updatedAt()).isEqualTo(UPDATED_AT);

        assertThat(items).extracting(ProductAdminListItemInfo::availability).containsExactly(
            SaleAvailability.ON_SALE, SaleAvailability.SOLD_OUT, SaleAvailability.STOP_SELLING, SaleAvailability.STOP_SELLING
        );
        assertThat(items.get(1).visible()).as("숨김 상품도 그대로 싣는다").isFalse();
        assertThat(items.get(1).listPrice()).isNull();
        assertThat(items.get(3).skuCount()).as("SKU 가 없는 상품(집계 행 없음)은 0 · 판매 종료").isZero();
        assertThat(items.get(3).thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("결과가 비면 집계 · 썸네일 포트를 부르지 않고, 페이지 정보와 전체 건수는 그대로다")
    void emptyPageSkipsFollowUpQueries() {
        given(productRepositoryPort.searchForAdmin(any())).willReturn(new PageImpl<>(List.of(), PageRequest.of(5, 20), 42));

        Page<ProductAdminListItemInfo> page = processor().search(ALL, null);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getNumber()).isEqualTo(5);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getTotalElements()).isEqualTo(42);
        verifyNoInteractions(skuRepositoryPort, productImageRepositoryPort);
    }

    @Test
    @DisplayName("집계 · 썸네일은 그 페이지 상품 아이디로 한 번씩만 읽는다 (N+1 금지)")
    void bulkFollowUpQueries() {
        givenRows(row(1, SaleStatus.ON_SALE, true, null), row(2, SaleStatus.ON_SALE, true, null));
        given(skuRepositoryPort.aggregateByProductIds(anyCollection())).willReturn(List.of());
        given(productImageRepositoryPort.findThumbnailsByProductIds(anyCollection())).willReturn(List.of());

        processor().search(ALL, null);

        verify(skuRepositoryPort).aggregateByProductIds(List.of(1L, 2L));
        verify(productImageRepositoryPort).findThumbnailsByProductIds(List.of(1L, 2L));
    }

    private ProductAdminQueryProcessor processor() {
        return new ProductAdminQueryProcessor(productRepositoryPort, skuRepositoryPort, productImageRepositoryPort);
    }

    private void givenEmptyPage(int page, int size) {
        given(productRepositoryPort.searchForAdmin(any())).willReturn(new PageImpl<>(List.of(), PageRequest.of(page, size), 0));
    }

    private void givenRows(ProductAdminListQueryResult... rows) {
        given(productRepositoryPort.searchForAdmin(any())).willReturn(new PageImpl<>(List.of(rows), PageRequest.of(0, 20), rows.length));
    }

    private ProductAdminSearchCriteria capturedCriteria() {
        ArgumentCaptor<ProductAdminSearchCriteria> captor = ArgumentCaptor.forClass(ProductAdminSearchCriteria.class);
        verify(productRepositoryPort).searchForAdmin(captor.capture());
        return captor.getValue();
    }

    private static ProductAdminListQueryResult row(long productId, SaleStatus saleStatus, boolean visible, Long listPriceAmount) {
        return new ProductAdminListQueryResult(
            productId, "상품" + productId, ProductFixtures.BASE_PRICE, KRW, listPriceAmount, listPriceAmount == null ? null : KRW, saleStatus, visible, UPDATED_AT
        );
    }
}
