package com.commercefoundry.domainlayer.shippingfee.application.port.in;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;

public interface InternationalShippingPolicyAdminUseCase {

    /** 아직 설정되지 않았으면 null 이다. 해외 배송 기능 플래그와 무관하다. */
    InternationalShippingPolicyAdminResponse getInternationalPolicy();

    /**
     * 전체 교체. 정책이 없고 버전이 null 이면 만든다.
     *
     * @param adminMemberId 교체하는 어드민 회원 아이디 — access 토큰의 memberId 다(요청 본문으로 받지 않는다). 감사 줄의 행위자다 (#163)
     */
    InternationalShippingPolicyAdminResponse replaceInternationalPolicy(InternationalShippingPolicyReplaceCommand command, long adminMemberId);
}
