package com.commercefoundry.domainlayer.shippingfee.application.service;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingPolicyFactInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.presenter.OnboardingFactInternalPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.OnboardingFactInternalUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyFactProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingFactInternalFacade implements OnboardingFactInternalUseCase {

    private final ShippingPolicyFactProcessor shippingPolicyFactProcessor;
    private final OnboardingFactInternalPresenter onboardingFactInternalPresenter;

    /** 외부 I/O 가 없는 읽기 두 문장이다. 안내 표시용 사실이라 두 조회 사이의 교체를 막으려고 격리 수준을 올리지 않는다. */
    @Override
    @Transactional(readOnly = true)
    public ShippingPolicyFactInternalResponse getShippingPolicyFact(long memberId) {
        return onboardingFactInternalPresenter.toResponse(shippingPolicyFactProcessor.getFact(memberId));
    }
}
