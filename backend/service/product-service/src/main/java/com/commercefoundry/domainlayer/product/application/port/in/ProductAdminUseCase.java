package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.persistence.dto.PageResponse;

/**
 * 어드민 상품 관리. 숨김 상품을 포함한다 — 고객 상품 조회({@link ProductWebUseCase})와 DTO 를 공유하지 않는다.
 */
public interface ProductAdminUseCase {

    /** 숨김을 포함한 편집용 저장 스냅숏. 삭제되었거나 없는 상품은 404 다. */
    ProductDetailAdminResponse getProduct(long productId);

    /**
     * 어드민 상품 목록. 조건이 null 이면 걸지 않는다 — 노출 여부 조건이 없으면 숨김 상품까지 전체다.
     * 카테고리 조건은 하위 카테고리를 포함하고, 없는 카테고리면 빈 페이지(전체 0건)다.
     */
    PageResponse<ProductListAdminItem> searchProducts(ProductAdminSearchCommand command);
}
