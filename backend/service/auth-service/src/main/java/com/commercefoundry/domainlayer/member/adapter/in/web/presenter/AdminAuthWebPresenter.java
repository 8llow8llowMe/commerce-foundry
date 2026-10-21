package com.commercefoundry.domainlayer.member.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.application.info.AdminAccessTokenInfo;
import org.springframework.stereotype.Component;

/**
 * 관리자 로그인 응답 (#6). 고객 토큰 쌍({@link AuthWebPresenter})과 모양이 달라 따로 둔다 — refresh 가 없다.
 */
@Component
public class AdminAuthWebPresenter {

    public AdminLoginResponse toLoginResponse(AdminAccessTokenInfo info) {
        return AdminLoginResponse.builder()
            .accessToken(info.accessToken())
            .accessTokenExpiresIn(info.accessTokenExpiresIn())
            .build();
    }
}
