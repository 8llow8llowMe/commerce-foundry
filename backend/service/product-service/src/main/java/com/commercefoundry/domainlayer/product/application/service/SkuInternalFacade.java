package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.SkuInternalResponse;
import com.commercefoundry.domainlayer.product.adapter.in.internal.presenter.SkuInternalPresenter;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.SkuInternalProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SkuInternalFacade implements SkuInternalUseCase {

    private final SkuInternalProcessor skuInternalProcessor;
    private final SkuInternalPresenter skuInternalPresenter;

    /**
     * 주문 금액의 원천이라 SKU 추가금과 상품 기본가를 같은 스냅숏에서 읽는다. 읽기 전용이라 직렬화 실패가 없다.
     *
     * <p>READ COMMITTED 면 SKU · 상품 · 옵션을 문장마다 다른 스냅숏으로 읽어, 그 사이 어드민 변경이 커밋되면 한 번도 존재하지 않은 가격
     * (새 추가금 + 옛 기본가)이 주문 스냅숏에 들어갈 수 있다. 고객 목록 · 상세는 표시만 영향을 받아 기본 격리 수준을 쓴다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SkuInternalResponse getSkus(List<Long> skuIds) {
        return skuInternalPresenter.toResponse(skuInternalProcessor.getSkus(skuIds));
    }
}
