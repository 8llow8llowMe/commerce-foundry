package com.commercefoundry.domainlayer.display.application.service;

import com.commercefoundry.domainlayer.display.adapter.in.web.dto.response.DisplayMainResponse;
import com.commercefoundry.domainlayer.display.adapter.in.web.presenter.DisplayWebPresenter;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayWebUseCase;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 메인 전시. 쿼리는 섹션 1 + 아이템 1(보이는 섹션 in 절) + 상품 카드 3(모든 섹션의 상품 아이디를 합쳐 한 번 — 상품 행 · SKU 집계 · 대표 이미지)이다.
 * 상품 카드는 상품 컨텍스트 {@link ProductListProcessor#summarizeVisible} 로 받아 상품 목록과 같은 값을 낸다 — 숨긴 상품은 거기서 빠진다.
 */
@Service
@RequiredArgsConstructor
public class DisplayWebFacade implements DisplayWebUseCase {

    private final DisplaySectionProcessor displaySectionProcessor;
    private final ProductListProcessor productListProcessor;
    private final DisplayWebPresenter displayWebPresenter;

    @Override
    @Transactional(readOnly = true)
    public DisplayMainResponse getMain() {
        List<DisplaySectionContent> contents = displaySectionProcessor.getLiveContents();
        Map<Long, ProductSummary> visibleProducts = productListProcessor.summarizeVisible(displaySectionProcessor.productIdsOf(contents));
        return displayWebPresenter.toMainResponse(displaySectionProcessor.toMainInfo(contents, visibleProducts));
    }
}
