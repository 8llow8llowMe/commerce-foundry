package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 토큰 재발급 요청. 필드 검증(400)을 두지 않는다 — 없음 · 빈 값 · 모양 오류를 모두 401(AUTH_010)로 거절해 "refresh 토큰이 쓸 수 없다" 신호를 하나로 둔다.
 */
@Schema(description = "토큰 재발급 요청")
public record TokenReissueRequest(

    @Schema(description = "로그인 · 직전 재발급에서 받은 refresh 토큰. 없거나 모양이 틀리면 401(AUTH_010)", example = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg")
    String refreshToken
) {

    /** 토큰 원문을 싣지 않는다. */
    @Override
    public String toString() {
        return "TokenReissueRequest[refreshToken=****]";
    }
}
