package com.commercefoundry.domainlayer.member.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.application.info.AdminAccessTokenInfo;
import com.commercefoundry.domainlayer.member.application.info.AdminLoginChallengeInfo;
import org.springframework.stereotype.Component;

/**
 * 관리자 로그인 응답 (#6 · #238 PR-B). 고객 토큰 쌍({@link AuthWebPresenter})과 모양이 달라 따로 둔다 — refresh 가 없고, OTP 등록 관리자의 1단계는 챌린지만 준다.
 */
@Component
public class AdminAuthWebPresenter {

    public AdminLoginResponse toLoginResponse(AdminAccessTokenInfo info) {
        return AdminLoginResponse.builder()
            .accessToken(info.accessToken())
            .accessTokenExpiresIn(info.accessTokenExpiresIn())
            .otpRequired(false)
            .build();
    }

    public AdminLoginResponse toOtpRequiredResponse(AdminLoginChallengeInfo info) {
        return AdminLoginResponse.builder()
            .otpRequired(true)
            .challengeToken(info.challengeToken())
            .challengeExpiresIn(info.expiresIn())
            .build();
    }
}
