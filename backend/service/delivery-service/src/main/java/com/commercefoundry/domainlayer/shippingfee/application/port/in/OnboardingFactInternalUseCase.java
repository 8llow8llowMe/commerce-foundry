package com.commercefoundry.domainlayer.shippingfee.application.port.in;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingPolicyFactInternalResponse;

public interface OnboardingFactInternalUseCase {

    /** 운영자 온보딩의 "국내 배송비 정책 저장" 사실 (#357). {@code memberId} 는 호출 서비스(operations)가 access 토큰으로 이미 확인한 운영자다. */
    ShippingPolicyFactInternalResponse getShippingPolicyFact(long memberId);
}
