package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductAvailabilityFilter;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Component;

/**
 * 어드민 상품 조회 전용 — 쓰지 않는다. 트랜잭션은 호출한 Facade 가 읽기 전용으로 건다.
 *
 * <p>목록의 쿼리는 목록 → count → SKU 집계(재고 left join 포함) → 대표 이미지 4번이다. 집계 · 이미지는 그 페이지의 상품 아이디 in 절 한 번씩이고 (N+1 금지),
 * 결과가 비면 집계 · 이미지를 읽지 않는다. count 는 첫 페이지가 다 차지 않으면 어댑터가 건너뛴다.
 */
@Component
@RequiredArgsConstructor
public class ProductAdminQueryProcessor {

    private final ProductRepositoryPort productRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final ProductImageRepositoryPort productImageRepositoryPort;

    /**
     * @param categoryIds 요청 카테고리를 하위까지 펼친 집합. 카테고리 조건이 없으면 null, 없는 카테고리면 빈 집합(쿼리 없이 빈 페이지)이다
     */
    public Page<ProductAdminListItemInfo> search(ProductAdminSearchCommand command, Set<Long> categoryIds) {
        Page<ProductAdminListQueryResult> rows = productRepositoryPort.searchForAdmin(toCriteria(command, categoryIds));
        if (!rows.hasContent()) {
            return new PageImpl<>(List.of(), rows.getPageable(), rows.getTotalElements());
        }

        List<Long> productIds = rows.getContent().stream().map(ProductAdminListQueryResult::productId).toList();
        Map<Long, SkuAggregateQueryResult> aggregates = skuRepositoryPort.aggregateByProductIds(productIds).stream()
            .collect(Collectors.toMap(SkuAggregateQueryResult::productId, Function.identity()));
        // 상품당 대표 이미지는 최대 1건이다. 데이터 오류로 둘 이상이면 아이디가 작은 쪽을 쓴다 — 고객 목록과 같다
        Map<Long, ProductImage> thumbnails = productImageRepositoryPort.findThumbnailsByProductIds(productIds).stream()
            .collect(Collectors.toMap(ProductImage::productId, Function.identity(), (left, right) -> left.id() <= right.id() ? left : right));

        return rows.map(row -> toInfo(row, aggregates.get(row.productId()), thumbnails.get(row.productId())));
    }

    /** 요청 값 → 조회 조건. 검색어는 앞뒤 공백을 빼고 비면 조건에서 뺀다({@link ProductAdminSearchCriteria}). */
    private static ProductAdminSearchCriteria toCriteria(ProductAdminSearchCommand command, Set<Long> categoryIds) {
        ProductSaleStatusFilter saleStatus = command.saleStatus();
        ProductAvailabilityFilter availability = command.availability();
        return ProductAdminSearchCriteria.builder()
            .keyword(command.keyword())
            .categoryIds(categoryIds)
            .saleStatus(saleStatus == null ? null : saleStatus.toSaleStatus())
            .visible(command.visible())
            .availability(availability == null ? null : availability.toSaleAvailability())
            .sortType(command.sortType())
            .page(command.page())
            .size(command.size())
            .build();
    }

    /**
     * 판매 가능 상태는 고객 목록 뱃지와 같은 규칙({@link SaleAvailability#ofProduct})이다 — 조회 조건의 SQL 판정도 같은 규칙이라 필터 결과와 표시가 맞는다.
     *
     * @param aggregate SKU 집계. SKU 가 하나도 없는 상품은 행이 없어 null 이다 — 판매중 · 구매 가능 SKU 없음, SKU 0개로 본다
     */
    private static ProductAdminListItemInfo toInfo(ProductAdminListQueryResult row, SkuAggregateQueryResult aggregate, ProductImage thumbnail) {
        boolean hasOnSaleSku = aggregate != null && aggregate.hasOnSaleSku();
        boolean hasPurchasableSku = aggregate != null && aggregate.hasPurchasableSku();

        return ProductAdminListItemInfo.builder()
            .productId(row.productId())
            .name(row.name())
            .thumbnailUrl(thumbnail == null ? null : thumbnail.url())
            .salePrice(row.salePrice())
            .listPrice(row.listPrice())
            .saleStatus(row.saleStatus())
            .visible(row.visible())
            .availability(SaleAvailability.ofProduct(row.saleStatus(), hasOnSaleSku, hasPurchasableSku))
            .skuCount(aggregate == null ? 0 : aggregate.skuCount())
            .updatedAt(row.updatedAt())
            .build();
    }
}
