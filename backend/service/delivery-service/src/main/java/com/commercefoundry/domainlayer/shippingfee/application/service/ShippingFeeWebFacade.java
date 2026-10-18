package com.commercefoundry.domainlayer.shippingfee.application.service;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.InternationalShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.ShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.presenter.ShippingFeeWebPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingFeeWebUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.InternationalShippingPolicyProcessor;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShippingFeeWebFacade implements ShippingFeeWebUseCase {

    private final ShippingPolicyProcessor shippingPolicyProcessor;
    private final InternationalShippingPolicyProcessor internationalShippingPolicyProcessor;
    private final ShippingFeeWebPresenter shippingFeeWebPresenter;

    @Override
    @Transactional(readOnly = true)
    public ShippingPolicyResponse getDomesticPolicy() {
        return shippingPolicyProcessor.findDomesticPolicy().map(shippingFeeWebPresenter::toResponse).orElse(null);
    }

    /**
     * 해외 정책은 정책 행 · 국가 그룹 · 국가 · 무게 구간 네 테이블을 따로 읽는다. 교체는 하위 행을 모두 지우고 아이디를 새로 발급해 넣으므로,
     * READ COMMITTED 로 문장마다 스냅숏이 다르면 새 그룹 + 옛 국가 · 구간처럼 아이디가 엇갈려 그룹이 비고 {@code ShippingZone} 이
     * {@link IllegalArgumentException}(500)을 낸다. 국내 안내는 정책 행 + 구간 하나라 엇갈려도 표시만 잠깐 섞이고 실패하지 않아 기본 격리 수준을 쓴다 —
     * 해외만 판단이 다르다. 읽기 전용이라 직렬화 실패가 없다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InternationalShippingPolicyResponse getInternationalPolicy() {
        return internationalShippingPolicyProcessor.findOfferedPolicy().map(shippingFeeWebPresenter::toResponse).orElse(null);
    }
}
