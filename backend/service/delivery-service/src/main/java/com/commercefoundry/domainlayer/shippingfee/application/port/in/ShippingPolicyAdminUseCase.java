package com.commercefoundry.domainlayer.shippingfee.application.port.in;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;

public interface ShippingPolicyAdminUseCase {

    /** 아직 설정되지 않았으면 null 이다. */
    ShippingPolicyAdminResponse getDomesticPolicy();

    /** 전체 교체. 정책이 없고 버전이 null 이면 만든다. */
    ShippingPolicyAdminResponse replaceDomesticPolicy(ShippingPolicyReplaceCommand command);
}
