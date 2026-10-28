package com.commercefoundry.domainlayer.look.application.service;

import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookDetailResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookSummaryResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.ProductLookListResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.presenter.LookWebPresenter;
import com.commercefoundry.domainlayer.look.application.port.in.LookWebUseCase;
import com.commercefoundry.domainlayer.look.application.service.processor.LookWebProcessor;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.persistence.dto.SliceResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 룩 조회. 쿼리는 룩 1 + 아이템 1(룩 아이디 in 절) + 상품 카드 4(모든 룩의 상품 아이디를 합쳐 한 번 — 상품 행 · SKU 집계 · 대표 이미지 · 정지 이미지)다.
 * 상품 카드는 상품 컨텍스트 {@link ProductListProcessor#summarizeVisible} 로 받아 상품 목록과 같은 값을 낸다 — 숨긴 상품은 거기서 빠진다.
 * 상품별 룩은 상품 컨텍스트 {@link ProductDetailProcessor#getVisibleProduct} 로 상품을 먼저 확인한다(없거나 숨김 → 404 {@code PRODUCT_001}, 플래그보다 앞).
 * 노출 판정(플래그 · 숨긴 룩)은 {@link LookWebProcessor} 가 한다. 외부 I/O 가 없어 읽기 전용 트랜잭션을 건다.
 */
@Service
@RequiredArgsConstructor
public class LookWebFacade implements LookWebUseCase {

    private final LookWebProcessor lookWebProcessor;
    private final ProductListProcessor productListProcessor;
    private final ProductDetailProcessor productDetailProcessor;
    private final LookWebPresenter lookWebPresenter;

    @Override
    @Transactional(readOnly = true)
    public SliceResponse<LookSummaryResponse> getLooks(int page, int size) {
        Slice<LookContent> slice = lookWebProcessor.getVisibleSlice(page, size);
        Map<Long, ProductSummary> visibleProducts = productListProcessor.summarizeVisible(LookContent.productIdsOf(slice.getContent()));
        return lookWebPresenter.toSliceResponse(slice.map(content -> lookWebProcessor.toInfo(content, visibleProducts)));
    }

    @Override
    @Transactional(readOnly = true)
    public LookDetailResponse getLook(long lookId) {
        LookContent content = lookWebProcessor.getVisibleContent(lookId);
        Map<Long, ProductSummary> visibleProducts = productListProcessor.summarizeVisible(content.productIds());
        return lookWebPresenter.toDetailResponse(lookWebProcessor.toInfo(content, visibleProducts));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductLookListResponse getLooksOfProduct(long productId) {
        productDetailProcessor.getVisibleProduct(productId);
        List<LookContent> contents = lookWebProcessor.getVisibleContentsOfProduct(productId);
        Map<Long, ProductSummary> visibleProducts = productListProcessor.summarizeVisible(LookContent.productIdsOf(contents));
        return lookWebPresenter.toProductLookListResponse(lookWebProcessor.toInfos(contents, visibleProducts));
    }
}
