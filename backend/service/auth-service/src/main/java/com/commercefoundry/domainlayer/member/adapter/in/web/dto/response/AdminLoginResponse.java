package com.commercefoundry.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "관리자 로그인 응답 — access 토큰만 준다(refresh 없음, 세션은 access 수명까지). 응답은 Cache-Control: no-store 다")
public record AdminLoginResponse(

    @Schema(description = "access 토큰(JWT, role=ADMIN). Authorization: Bearer 로 보낸다", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6ImF0K2p3dCJ9...")
    String accessToken,

    @Schema(description = "access 토큰의 남은 수명(초). 만료되면 다시 로그인한다", example = "1800")
    long accessTokenExpiresIn
) {

    /** 토큰 원문이 로그 · 예외 메시지로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "AdminLoginResponse[accessToken=****, accessTokenExpiresIn=" + accessTokenExpiresIn + "]";
    }
}
