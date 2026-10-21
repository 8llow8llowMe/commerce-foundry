package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductAvailabilityFilter;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleStatusFilter;
import java.util.Objects;
import lombok.Builder;

/**
 * 어드민 상품 목록 요청 값 그대로. null 인 조건은 걸지 않는다. 카테고리를 하위까지 펼치고 domain 조건으로 바꾸는 일은 Facade · Processor 가 한다.
 *
 * @param keyword 상품명 부분 일치. 앞뒤 공백은 조건을 만들 때 뺀다
 * @param categoryId 진열 카테고리 — 하위 카테고리를 포함한다
 * @param visible 노출 여부. null 이면 숨김 상품까지 전체다
 */
@Builder
public record ProductAdminSearchCommand(
    String keyword, Long categoryId, ProductSaleStatusFilter saleStatus, Boolean visible, ProductAvailabilityFilter availability,
    ProductAdminSortType sortType, int page, int size
) {

    public ProductAdminSearchCommand {
        Objects.requireNonNull(sortType, "sortType");
    }
}
