package com.commercefoundry.domainlayer.product.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductSearchCriteriaTest {

    @Test
    @DisplayName("첫 페이지 · 최소 크기 · 전체 카테고리(null)를 받는다")
    void accepts() {
        ProductSearchCriteria criteria = new ProductSearchCriteria(null, ProductSortType.NEWEST, 0, 1);

        assertThat(criteria.categoryIds()).isNull();
        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("page 가 음수면 거절한다")
    void rejectsNegativePage() {
        assertThatThrownBy(() -> new ProductSearchCriteria(null, ProductSortType.NEWEST, -1, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("size 가 1 미만이면 거절한다")
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> new ProductSearchCriteria(null, ProductSortType.NEWEST, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductSearchCriteria(null, ProductSortType.NEWEST, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정렬은 필수다")
    void requiresSortType() {
        assertThatThrownBy(() -> new ProductSearchCriteria(null, null, 0, 20)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("카테고리 집합은 방어 복사한다 — 넘긴 쪽이 원본을 바꿔도 조건이 바뀌지 않고, 꺼낸 집합은 바꿀 수 없다")
    void copiesCategoryIds() {
        Set<Long> categoryIds = new HashSet<>(Set.of(10L, 11L));
        ProductSearchCriteria criteria = new ProductSearchCriteria(categoryIds, ProductSortType.PRICE_ASC, 0, 20);

        categoryIds.add(12L);

        assertThat(criteria.categoryIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThatThrownBy(() -> criteria.categoryIds().add(13L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("빈 카테고리 집합은 null(전체)과 다르게 빈 집합으로 남는다")
    void keepsEmptyCategoryIds() {
        assertThat(new ProductSearchCriteria(Set.of(), ProductSortType.NEWEST, 0, 20).categoryIds()).isNotNull().isEmpty();
    }
}
