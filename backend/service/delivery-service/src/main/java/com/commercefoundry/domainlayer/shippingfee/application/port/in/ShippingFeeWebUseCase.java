package com.commercefoundry.domainlayer.shippingfee.application.port.in;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.InternationalShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.ShippingPolicyResponse;

public interface ShippingFeeWebUseCase {

    /** 스토어프론트 배송 안내용 국내 배송비 정책. 아직 설정되지 않았으면 null 이다. */
    ShippingPolicyResponse getDomesticPolicy();

    /** 스토어프론트 해외 배송 안내용 정책. 해외 배송이 꺼진 스토어이거나 아직 설정되지 않았으면 null 이다. */
    InternationalShippingPolicyResponse getInternationalPolicy();
}
