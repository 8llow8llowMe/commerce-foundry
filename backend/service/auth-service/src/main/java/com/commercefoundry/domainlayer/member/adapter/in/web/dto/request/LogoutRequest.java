package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그아웃 요청. refresh 토큰은 선택이다 — 없거나 모르는 값이어도 access 토큰은 차단 목록에 넣고 200 이다(멱등).
 */
@Schema(description = "로그아웃 요청")
public record LogoutRequest(

    @Schema(description = "폐기할 refresh 토큰 — 이 회원의 것일 때만 그 로그인(family)의 토큰을 모두 폐기한다. 없으면 access 토큰만 막는다",
        example = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg", nullable = true)
    String refreshToken
) {

    /** 토큰 원문을 싣지 않는다. */
    @Override
    public String toString() {
        return "LogoutRequest[refreshToken=" + (refreshToken == null ? "null" : "****") + "]";
    }
}
