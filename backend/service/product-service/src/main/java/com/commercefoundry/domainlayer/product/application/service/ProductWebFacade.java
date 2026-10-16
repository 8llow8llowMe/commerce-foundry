package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductDetailResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.presenter.ProductWebPresenter;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.in.ProductWebUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.persistence.dto.SliceResponse;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 상품 조회. 카테고리는 카테고리 컨텍스트의 {@link CategoryProcessor} 용도별 메서드로만 받는다 — 숨김 규칙은 그 컨텍스트가 정하고,
 * 그 컨텍스트의 포트 · 어댑터를 직접 주입하지 않는다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductWebFacade implements ProductWebUseCase {

    private final CategoryProcessor categoryProcessor;
    private final ProductListProcessor productListProcessor;
    private final ProductDetailProcessor productDetailProcessor;
    private final ProductWebPresenter productWebPresenter;

    /**
     * 카테고리 조건은 하위 카테고리까지 펼친다 — 숨김 여부와 무관하다(숨김은 메뉴에서만 뺀다). 없는 아이디는 빈 집합이라 어댑터가 쿼리 없이 빈 목록을 준다.
     */
    @Override
    public SliceResponse<ProductListItemResponse> searchProducts(Long categoryId, ProductSortType sortType, int page, int size) {
        Set<Long> categoryIds = categoryId == null ? null : categoryProcessor.subtreeIdsOf(categoryId);
        ProductSearchCriteria criteria = new ProductSearchCriteria(categoryIds, sortType, page, size);
        return productWebPresenter.toListResponse(productListProcessor.search(criteria));
    }

    /** 상품을 먼저 읽는다 — 없거나 숨긴 상품이면 카테고리를 읽지 않고 404 다. */
    @Override
    public ProductDetailResponse getProduct(long productId) {
        Product product = productDetailProcessor.getVisibleProduct(productId);
        List<Category> categoryPath = categoryProcessor.visiblePathOf(product.categoryId());
        return productWebPresenter.toDetailResponse(productDetailProcessor.getDetail(product, categoryPath));
    }
}
