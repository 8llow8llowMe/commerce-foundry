package com.commercefoundry.domainlayer.product.application.model;

import java.util.Objects;
import java.util.Set;

/**
 * 고객 상품 목록 조회 조건.
 *
 * @param categoryIds 진열 카테고리 아이디 (하위 포함으로 펼친 집합). null 이면 전체, 빈 집합이면 결과가 없다
 * @param page 0부터
 * @param size 1 이상. 상한은 요청 검증이 정한다
 */
public record ProductSearchCriteria(Set<Long> categoryIds, ProductSortType sortType, int page, int size) {

    public ProductSearchCriteria {
        Objects.requireNonNull(sortType, "sortType");
        if (page < 0) {
            throw new IllegalArgumentException("page 는 0 이상이어야 합니다: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size 는 1 이상이어야 합니다: " + size);
        }
        categoryIds = categoryIds == null ? null : Set.copyOf(categoryIds);
    }
}
