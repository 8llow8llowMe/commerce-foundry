package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminTreeResponse;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;

/**
 * 어드민 카테고리 관리. 카테고리 컨텍스트가 아니라 상품 컨텍스트에 둔다 — 삭제 판정의 "연결된 상품" 을 상품 컨텍스트가 알기 때문이다(의존 방향 product → category).
 */
public interface CategoryAdminUseCase {

    /** 숨김 포함 전체 트리. 같은 상위 안에서 노출 순서 → 아이디 오름차순이다. */
    CategoryAdminTreeResponse getCategoryTree();

    /** 없는 상위는 {@code PRODUCT_044}(400), 3단계를 넘으면 {@code PRODUCT_040}(400)이다. */
    CategoryAdminResponse createCategory(CategoryUpsertCommand command);

    /**
     * 전체 교체(상위 이동 포함). 없는 카테고리는 {@code PRODUCT_004}(404), 없는 상위는 {@code PRODUCT_044}, 깊이 초과는 {@code PRODUCT_040},
     * 자기 자신 · 하위를 상위로 지정하면 {@code PRODUCT_041}(모두 400)이다.
     */
    CategoryAdminResponse updateCategory(long categoryId, CategoryUpsertCommand command);

    /** 없으면 {@code PRODUCT_004}(404), 하위가 있으면 {@code PRODUCT_042}, 상품의 대표 · 진열 카테고리면 {@code PRODUCT_043}(둘 다 409)이다. */
    void deleteCategory(long categoryId);
}
