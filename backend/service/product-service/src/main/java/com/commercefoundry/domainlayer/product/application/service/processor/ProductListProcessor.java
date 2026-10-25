package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.info.ProductListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleSummary;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.global.properties.StoreProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Component;

/**
 * 고객 상품 카드(목록 항목). 쿼리는 상품 행 → SKU 집계(재고 left join 포함) → 대표 이미지 → 정지 이미지 4번이고,
 * 집계 · 이미지는 읽은 상품 아이디로 한 번에 읽는다 (N+1 금지).
 *
 * <p>카드 값은 {@link #summarize} 한 곳에서 만든다 — 상품 목록({@link #search})과 같은 서비스의 전시 컨텍스트(메인 상품 픽, {@link #summarizeVisible})가
 * 같은 계산({@link ProductSaleSummary#of})을 타서 같은 상품에 같은 가격 · 할인 · 판매 상태를 보인다.
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

        List<ProductListItemInfo> items = summarize(rows.getContent()).stream().map(ProductListItemInfo::from).toList();
        return new SliceImpl<>(items, rows.getPageable(), rows.hasNext());
    }

    /**
     * 다른 컨텍스트(전시)용 — 아이디 목록 중 <b>고객에게 보이는 상품</b>의 카드. 숨긴 상품 · 없는 아이디는 결과에 없다(호출한 쪽이 뺀다).
     * 쿼리는 상품 행 · SKU 집계 · 대표 이미지 · 정지 이미지 각 1번이고(아이디 in 절), 빈 컬렉션이면 쿼리 없이 빈 맵이다.
     *
     * @return 상품 아이디 → 카드. 순서는 의미가 없다 — 호출한 쪽이 자기 순서로 꺼낸다
     */
    public Map<Long, ProductSummary> summarizeVisible(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<ProductListQueryResult> rows = productRepositoryPort.findVisibleListRowsByIds(productIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        return summarize(rows).stream()
            .collect(Collectors.toMap(ProductSummary::productId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    /** 행 순서를 지킨다. 집계 · 대표 이미지 · 정지 이미지는 행의 상품 아이디로 한 번씩 읽는다. */
    private List<ProductSummary> summarize(List<ProductListQueryResult> rows) {
        List<Long> productIds = rows.stream().map(ProductListQueryResult::productId).toList();
        Map<Long, SkuAggregateQueryResult> aggregates = skuRepositoryPort.aggregateByProductIds(productIds).stream()
            .collect(Collectors.toMap(SkuAggregateQueryResult::productId, Function.identity()));
        // 상품당 대표 이미지는 최대 1건이다. 데이터 오류로 둘 이상이면 아이디가 작은 쪽을 쓴다 — 응답을 실패시키지 않고 결과는 매번 같다
        Map<Long, ProductImage> thumbnails = productImageRepositoryPort.findThumbnailsByProductIds(productIds).stream()
            .collect(Collectors.toMap(ProductImage::productId, Function.identity(), (left, right) -> left.id() <= right.id() ? left : right));
        Map<Long, ProductImage> hoverImages = secondStillImageByProduct(productImageRepositoryPort.findStillImagesByProductIds(productIds));

        return rows.stream()
            .map(row -> toSummary(row, aggregates.get(row.productId()), thumbnails.get(row.productId()), hoverImages.get(row.productId())))
            .toList();
    }

    /**
     * 상품마다 정지 이미지(영상 제외)를 노출 순서 → 아이디 오름차순으로 세워 두 번째를 고른다. 1장 이하면 없다.
     */
    private static Map<Long, ProductImage> secondStillImageByProduct(List<ProductImage> images) {
        Map<Long, List<ProductImage>> byProduct = new LinkedHashMap<>();
        for (ProductImage image : images) {
            if (image.imageType().isVideo()) {
                continue;
            }
            byProduct.computeIfAbsent(image.productId(), id -> new ArrayList<>()).add(image);
        }
        Map<Long, ProductImage> second = new LinkedHashMap<>();
        for (Map.Entry<Long, List<ProductImage>> entry : byProduct.entrySet()) {
            List<ProductImage> ordered = new ArrayList<>(entry.getValue());
            ordered.sort(Comparator.comparingInt(ProductImage::displayOrder).thenComparingLong(ProductImage::id));
            if (ordered.size() >= 2) {
                second.put(entry.getKey(), ordered.get(1));
            }
        }
        return second;
    }

    /**
     * @param aggregate 판매중 SKU 집계. SKU 가 하나도 없는 상품은 집계 행이 없어 null 이다 — 판매중 · 구매 가능 SKU 없음으로 본다
     */
    private ProductSummary toSummary(ProductListQueryResult row, SkuAggregateQueryResult aggregate, ProductImage thumbnail, ProductImage hoverImage) {
        ProductSaleSummary summary = aggregate == null
            ? ProductSaleSummary.of(row.saleStatus(), row.salePrice(), row.listPrice(), false, false, null, null)
            : ProductSaleSummary.of(
                row.saleStatus(), row.salePrice(), row.listPrice(),
                aggregate.hasOnSaleSku(), aggregate.hasPurchasableSku(), aggregate.minOnSaleAdditionalAmount(), aggregate.maxOnSaleAdditionalAmount()
            );

        return new ProductSummary(
            row.productId(), row.name(), thumbnail, hoverImage,
            summary.salePrice(), summary.listPrice(), summary.discountRate(), summary.hasOptionSurcharge(),
            summary.saleStatus(), storeProperties.features().customOrder() && row.customOrder(),
            storeProperties.features().preorder() && row.preorder()
        );
    }
}
