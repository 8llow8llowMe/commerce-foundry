package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.MemberProfileResponse;
import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SignUpResponse;
import com.commercefoundry.domainlayer.member.application.command.MemberProfileUpdateCommand;
import com.commercefoundry.domainlayer.member.application.command.SignUpCommand;

public interface MemberWebUseCase {

    /**
     * 고객 회원가입. 이미 가입된 이메일은 {@code AUTH_002}(409) 다.
     * 같은 이메일의 가입 시도가 허용 횟수를 넘으면 {@code AUTH_041}(429, {@code LoginAttemptLimitedException}) 이다 (#66 — 계수기 장애면 세지 않고 통과).
     */
    SignUpResponse signUp(SignUpCommand command);

    /** 내 정보. 회원이 없으면 {@code AUTH_003}(404) 다. */
    MemberProfileResponse getMyProfile(long memberId);

    /** 내 정보 부분 수정. 수정 충돌은 {@code AUTH_004}(409) 다. */
    MemberProfileResponse updateMyProfile(long memberId, MemberProfileUpdateCommand command);
}
