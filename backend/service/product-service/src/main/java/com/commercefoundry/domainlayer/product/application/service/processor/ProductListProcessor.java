package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.info.ProductImageInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleSummary;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.global.properties.StoreProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Component;

/**
 * 고객 상품 목록. 쿼리는 목록 → SKU 집계(재고 left join 포함) → 대표 이미지 3번이고, 집계 · 이미지는 그 페이지의 상품 아이디로 한 번에 읽는다 (N+1 금지).
 */
@Component
@RequiredArgsConstructor
public class ProductListProcessor {

    private final ProductRepositoryPort productRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final ProductImageRepositoryPort productImageRepositoryPort;
    private final StoreProperties storeProperties;

    public Slice<ProductListItemInfo> search(ProductSearchCriteria criteria) {
        Slice<ProductListQueryResult> rows = productRepositoryPort.searchVisible(criteria);
        if (!rows.hasContent()) {
            return new SliceImpl<>(List.of(), rows.getPageable(), rows.hasNext());
        }

        List<Long> productIds = rows.getContent().stream().map(ProductListQueryResult::productId).toList();
        Map<Long, SkuAggregateQueryResult> aggregates = skuRepositoryPort.aggregateByProductIds(productIds).stream()
            .collect(Collectors.toMap(SkuAggregateQueryResult::productId, Function.identity()));
        // 상품당 대표 이미지는 최대 1건이다. 데이터 오류로 둘 이상이면 아이디가 작은 쪽을 쓴다 — 응답을 실패시키지 않고 결과는 매번 같다
        Map<Long, ProductImage> thumbnails = productImageRepositoryPort.findThumbnailsByProductIds(productIds).stream()
            .collect(Collectors.toMap(ProductImage::productId, Function.identity(), (left, right) -> left.id() <= right.id() ? left : right));

        return rows.map(row -> toInfo(row, aggregates.get(row.productId()), thumbnails.get(row.productId())));
    }

    /**
     * @param aggregate 판매중 SKU 집계. SKU 가 하나도 없는 상품은 집계 행이 없어 null 이다 — 판매중 · 구매 가능 SKU 없음으로 본다
     */
    private ProductListItemInfo toInfo(ProductListQueryResult row, SkuAggregateQueryResult aggregate, ProductImage thumbnail) {
        ProductSaleSummary summary = aggregate == null
            ? ProductSaleSummary.of(row.saleStatus(), row.salePrice(), row.listPrice(), false, false, null, null)
            : ProductSaleSummary.of(
                row.saleStatus(), row.salePrice(), row.listPrice(),
                aggregate.hasOnSaleSku(), aggregate.hasPurchasableSku(), aggregate.minOnSaleAdditionalAmount(), aggregate.maxOnSaleAdditionalAmount()
            );

        return ProductListItemInfo.builder()
            .productId(row.productId())
            .name(row.name())
            .thumbnail(thumbnail == null ? null : ProductImageInfo.from(thumbnail))
            .salePrice(summary.salePrice())
            .listPrice(summary.listPrice())
            .discountRate(summary.discountRate())
            .hasOptionSurcharge(summary.hasOptionSurcharge())
            .saleStatus(summary.saleStatus())
            .customOrder(storeProperties.features().customOrder() && row.customOrder())
            .build();
    }
}
