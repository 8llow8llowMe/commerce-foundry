package com.commercefoundry.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "로그인 · 재발급 응답 — 토큰 한 쌍. 응답은 Cache-Control: no-store 다")
public record AuthTokenResponse(

    @Schema(description = "access 토큰(JWT). Authorization: Bearer 로 보낸다", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6ImF0K2p3dCJ9...")
    String accessToken,

    @Schema(description = "access 토큰의 남은 수명(초)", example = "1800")
    long accessTokenExpiresIn,

    @Schema(description = "refresh 토큰 — 불투명 base64url 43자. 재발급 · 로그아웃에 보낸다. 재발급할 때마다 새 값으로 바뀐다", example = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg")
    String refreshToken,

    @Schema(description = "refresh 토큰의 남은 수명(초). 서버가 건 TTL 과 같다 — 로그인 절대 수명(30일)이 가까우면 14일보다 짧다", example = "1209600")
    long refreshTokenExpiresIn
) {

    /** 토큰 원문이 로그 · 예외 메시지로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "AuthTokenResponse[accessToken=****, accessTokenExpiresIn=" + accessTokenExpiresIn
            + ", refreshToken=****, refreshTokenExpiresIn=" + refreshTokenExpiresIn + "]";
    }
}
