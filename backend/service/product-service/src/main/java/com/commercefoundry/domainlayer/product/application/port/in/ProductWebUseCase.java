package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductDetailResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.persistence.dto.SliceResponse;

public interface ProductWebUseCase {

    /**
     * 고객 상품 목록. 숨긴 상품은 빠지고 판매 종료 상품은 포함한다.
     *
     * @param categoryId 하위 카테고리를 포함해 거른다. null 이면 전체, 없는 아이디면 빈 목록
     */
    SliceResponse<ProductListItemResponse> searchProducts(Long categoryId, ProductSortType sortType, int page, int size);

    /** 고객 상품 상세. 없거나 숨긴 상품은 {@code PRODUCT_001}(404) 이다. */
    ProductDetailResponse getProduct(long productId);
}
