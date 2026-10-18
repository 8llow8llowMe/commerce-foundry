package com.commercefoundry.domainlayer.shippingfee.application.port.in;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;

public interface InternationalShippingPolicyAdminUseCase {

    /** 아직 설정되지 않았으면 null 이다. 해외 배송 기능 플래그와 무관하다. */
    InternationalShippingPolicyAdminResponse getInternationalPolicy();

    /** 전체 교체. 정책이 없고 버전이 null 이면 만든다. */
    InternationalShippingPolicyAdminResponse replaceInternationalPolicy(InternationalShippingPolicyReplaceCommand command);
}
