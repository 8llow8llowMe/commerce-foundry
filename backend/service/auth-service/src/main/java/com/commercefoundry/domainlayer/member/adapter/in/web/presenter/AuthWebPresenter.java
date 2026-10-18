package com.commercefoundry.domainlayer.member.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.application.info.AuthTokenInfo;
import org.springframework.stereotype.Component;

/**
 * 로그인 · 재발급 응답. 토큰 쌍을 그대로 옮긴다 — 둘 다 같은 모양이다.
 */
@Component
public class AuthWebPresenter {

    public AuthTokenResponse toTokenResponse(AuthTokenInfo info) {
        return AuthTokenResponse.builder()
            .accessToken(info.accessToken())
            .accessTokenExpiresIn(info.accessTokenExpiresIn())
            .refreshToken(info.refreshToken())
            .refreshTokenExpiresIn(info.refreshTokenExpiresIn())
            .build();
    }
}
