package com.commercefoundry.domainlayer.member.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.MemberProfileResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SignUpResponse;
import com.commercefoundry.domainlayer.member.application.info.MemberProfileInfo;
import com.commercefoundry.domainlayer.member.application.info.MemberSignUpInfo;
import org.springframework.stereotype.Component;

/**
 * 고객 회원 응답. 식별자 {@code long → String} 변환만 한다.
 */
@Component
public class MemberWebPresenter {

    public SignUpResponse toSignUpResponse(MemberSignUpInfo info) {
        return SignUpResponse.builder()
            .memberId(String.valueOf(info.memberId()))
            .build();
    }

    public MemberProfileResponse toProfileResponse(MemberProfileInfo info) {
        return MemberProfileResponse.builder()
            .memberId(String.valueOf(info.memberId()))
            .email(info.email())
            .name(info.name())
            .phone(info.phone())
            .marketingAgreed(info.marketingAgreed())
            .build();
    }
}
