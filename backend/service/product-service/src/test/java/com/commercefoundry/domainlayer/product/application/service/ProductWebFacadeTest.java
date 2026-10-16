package com.commercefoundry.domainlayer.product.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.product.adapter.in.web.presenter.ProductWebPresenter;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductDetailInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

@ExtendWith(MockitoExtension.class)
class ProductWebFacadeTest {

    // 숨김 규칙 자체는 CategoryProcessorTest 가 본다. 여기서는 Facade 가 용도별 메서드의 결과를 그대로 넘기는지만 본다
    private static final List<Category> VISIBLE_PATH = List.of(new Category(1, null, "상의", 0, true), new Category(1111, 111L, "숨김-하위", 0, true));
    private static final Slice<ProductListItemInfo> EMPTY = new SliceImpl<>(List.of(), PageRequest.of(0, 20), false);

    @Mock
    private CategoryProcessor categoryProcessor;

    @Mock
    private ProductListProcessor productListProcessor;

    @Mock
    private ProductDetailProcessor productDetailProcessor;

    @Mock
    private ProductWebPresenter productWebPresenter;

    private ProductWebFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ProductWebFacade(categoryProcessor, productListProcessor, productDetailProcessor, productWebPresenter);
    }

    @Test
    @DisplayName("카테고리 조건이 없으면 카테고리를 읽지 않고 전체로 찾는다")
    void searchWithoutCategory() {
        given(productListProcessor.search(any())).willReturn(EMPTY);

        facade.searchProducts(null, ProductSortType.PRICE_ASC, 2, 30);

        verify(productListProcessor).search(new ProductSearchCriteria(null, ProductSortType.PRICE_ASC, 2, 30));
        verifyNoInteractions(categoryProcessor);
        verify(productWebPresenter).toListResponse(EMPTY);
    }

    @Test
    @DisplayName("카테고리 조건은 CategoryProcessor.subtreeIdsOf 의 하위 집합(숨김 무관)으로 펼친다")
    void searchExpandsSubtreeIncludingHidden() {
        given(categoryProcessor.subtreeIdsOf(11L)).willReturn(Set.of(11L, 111L, 1111L));
        given(productListProcessor.search(any())).willReturn(EMPTY);

        facade.searchProducts(11L, ProductSortType.NEWEST, 0, 20);

        verify(productListProcessor).search(new ProductSearchCriteria(Set.of(11L, 111L, 1111L), ProductSortType.NEWEST, 0, 20));
    }

    @Test
    @DisplayName("없는 카테고리면 빈 집합으로 넘긴다 — 어댑터가 쿼리 없이 빈 목록을 준다")
    void searchWithUnknownCategory() {
        given(categoryProcessor.subtreeIdsOf(999L)).willReturn(Set.of());
        given(productListProcessor.search(any())).willReturn(EMPTY);

        facade.searchProducts(999L, ProductSortType.NEWEST, 0, 20);

        verify(productListProcessor).search(new ProductSearchCriteria(Set.of(), ProductSortType.NEWEST, 0, 20));
    }

    @Test
    @DisplayName("상세는 상품을 먼저 읽고, 대표 카테고리의 보이는 경로(CategoryProcessor.visiblePathOf)를 넘긴다")
    void detailPassesVisiblePath() {
        Product product = ProductFixtures.product(7L, 1111L);
        ProductDetailInfo detail = mock(ProductDetailInfo.class);
        given(productDetailProcessor.getVisibleProduct(7L)).willReturn(product);
        given(categoryProcessor.visiblePathOf(1111L)).willReturn(VISIBLE_PATH);
        given(productDetailProcessor.getDetail(any(), any())).willReturn(detail);

        facade.getProduct(7L);

        InOrder order = inOrder(productDetailProcessor, categoryProcessor);
        order.verify(productDetailProcessor).getVisibleProduct(7L);
        order.verify(categoryProcessor).visiblePathOf(1111L);
        order.verify(productDetailProcessor).getDetail(product, VISIBLE_PATH);
        verify(productWebPresenter).toDetailResponse(detail);
    }

    @Test
    @DisplayName("없거나 숨긴 상품이면 카테고리를 읽지 않고 PRODUCT_001 이다")
    void detailNotFound() {
        given(productDetailProcessor.getVisibleProduct(7L)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));

        assertThatThrownBy(() -> facade.getProduct(7L)).isInstanceOf(ProductException.class);
        verifyNoInteractions(categoryProcessor, productWebPresenter);
    }
}
