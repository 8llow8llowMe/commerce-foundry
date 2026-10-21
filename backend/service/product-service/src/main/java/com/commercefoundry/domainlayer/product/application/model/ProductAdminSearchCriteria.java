package com.commercefoundry.domainlayer.product.application.model;

import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import java.util.Objects;
import java.util.Set;
import lombok.Builder;

/**
 * 어드민 상품 목록 조회 조건. 숨김 상품을 포함한다 — {@code visible} 이 null 이면 노출 여부로 거르지 않는다. null 인 조건은 걸지 않는다.
 *
 * @param keyword 상품명 부분 일치(대소문자 무시). 앞뒤 공백을 뺀 값이고 비었으면 null 이다
 * @param categoryIds 진열 카테고리 아이디 (하위 포함으로 펼친 집합). null 이면 전체, 빈 집합이면 결과가 없다
 * @param availability 재고를 반영한 판매 가능 상태 — 고객 목록 뱃지와 같은 규칙({@link SaleAvailability#ofProduct})이다
 * @param page 0부터
 * @param size 1 이상. 상한은 요청 검증이 정한다
 */
@Builder
public record ProductAdminSearchCriteria(
    String keyword, Set<Long> categoryIds, SaleStatus saleStatus, Boolean visible, SaleAvailability availability,
    ProductAdminSortType sortType, int page, int size
) {

    public ProductAdminSearchCriteria {
        Objects.requireNonNull(sortType, "sortType");
        if (page < 0) {
            throw new IllegalArgumentException("page 는 0 이상이어야 합니다: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size 는 1 이상이어야 합니다: " + size);
        }
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        categoryIds = categoryIds == null ? null : Set.copyOf(categoryIds);
    }
}
