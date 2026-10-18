package com.commercefoundry.domainlayer.shippingfee.application.service;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.presenter.ShippingFeeInternalPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingFeeInternalUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingFeeQuoteProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShippingFeeInternalFacade implements ShippingFeeInternalUseCase {

    private final ShippingFeeQuoteProcessor shippingFeeQuoteProcessor;
    private final ShippingFeeInternalPresenter shippingFeeInternalPresenter;

    /**
     * 주문 금액에 들어가는 배송비라 정책 행과 도서산간 구간을 같은 스냅숏에서 읽는다. 읽기 전용이라 직렬화 실패가 없다.
     *
     * <p>READ COMMITTED 면 두 조회 사이에 어드민 교체가 커밋될 때 옛 기본 배송비 + 새 구간처럼 한 번도 존재하지 않은 정책으로 계산할 수 있다.
     * 고객 · 어드민 조회는 표시만 영향을 받아 기본 격리 수준을 쓴다 (product-service {@code SkuInternalFacade} 와 같은 판단).
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShippingFeeQuoteInternalResponse quote(ShippingFeeQuoteCommand command) {
        return shippingFeeInternalPresenter.toResponse(shippingFeeQuoteProcessor.quote(command));
    }
}
