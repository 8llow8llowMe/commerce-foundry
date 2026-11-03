package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductCreateAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductImageUploadAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductHeaderUpdateAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.ProductPricingUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCategoryUpdateCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductHeaderUpdateCommand;
import com.commercefoundry.persistence.dto.PageResponse;

/**
 * 어드민 상품 관리. 숨김 상품을 포함한다 — 고객 상품 조회({@link ProductWebUseCase})와 DTO 를 공유하지 않는다.
 */
public interface ProductAdminUseCase {

    ProductDetailAdminResponse updateOptions(com.commercefoundry.domainlayer.product.application.command.ProductOptionsUpdateCommand command);

    ProductDetailAdminResponse updateImages(com.commercefoundry.domainlayer.product.application.command.ProductImagesUpdateCommand command);

    /** 가격을 버전 대조 후 전체 교체한다. 동일 값은 버전을 유지하고 생략한 정가는 삭제한다. */
    ProductDetailAdminResponse updatePricing(ProductPricingUpdateCommand command);

    /** 대표 · 진열 카테고리를 버전 대조 후 전체 교체한다. */
    ProductDetailAdminResponse updateCategories(ProductCategoryUpdateCommand command);

    /** Versioned replacement of editable header fields only; product children and prices remain unchanged. */
    ProductHeaderUpdateAdminResponse updateHeader(ProductHeaderUpdateCommand command);

    /** 숨김을 포함한 편집용 저장 스냅숏. 삭제되었거나 없는 상품은 404 다. */
    ProductDetailAdminResponse getProduct(long productId);

    /** Soft delete; an already deleted product succeeds without a write. Missing products are 404. */
    void delete(long productId, long actorMemberId);

    /**
     * 어드민 상품 목록. 조건이 null 이면 걸지 않는다 — 노출 여부 조건이 없으면 숨김 상품까지 전체다.
     * 카테고리 조건은 하위 카테고리를 포함하고, 없는 카테고리면 빈 페이지(전체 0건)다.
     */
    PageResponse<ProductListAdminItem> searchProducts(ProductAdminSearchCommand command);

    /**
     * 상품 등록 (#9 E1) — 이미지 없이, 맞춤 제작 꺼짐으로 만든다. 상세 HTML 은 정제해 저장한다.
     * 없는 카테고리는 {@code PRODUCT_012}, 옵션 · SKU 구성은 {@code 013} · {@code 014}, 가격 규칙은 {@code 016}(400)이다.
     * 카테고리 쓰기와 겹쳐 잠금을 얻지 못하면 {@code PRODUCT_045}(409)다.
     */
    ProductCreateAdminResponse create(ProductCreateCommand command);

    /**
     * 상품 이미지 업로드 자리 발급 (#9 F) — 발급한 어드민에게 묶인 임시 키와 서명 주소. 형식은 {@code PRODUCT_033}, 크기는 {@code 034}(400),
     * 스토리지 장애는 {@code 035} · 꺼짐은 {@code 036}(503)이다.
     *
     * @param memberId access 토큰의 memberId
     */
    ProductImageUploadAdminResponse issueImageUpload(long memberId, String contentType, Long contentLength);
}
