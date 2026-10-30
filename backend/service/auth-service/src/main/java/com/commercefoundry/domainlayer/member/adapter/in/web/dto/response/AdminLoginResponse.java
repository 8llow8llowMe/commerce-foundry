package com.commercefoundry.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 관리자 로그인 응답 (#6 · #238 PR-B). 1단계 · 2단계가 같은 모양이다 — {@code otpRequired} 가 갈래를 정한다.
 * <ul>
 *   <li>{@code otpRequired=false} — access 토큰 두 칸이 차 있고 챌린지 두 칸은 null 이다(OTP 미등록 관리자의 1단계 · 2단계 성공)</li>
 *   <li>{@code otpRequired=true} — 챌린지 두 칸만 차 있고 access 두 칸은 null 이다(OTP 등록 관리자의 1단계). 2단계({@code …/login/otp})로 간다</li>
 * </ul>
 */
@Builder
@Schema(description = "관리자 로그인 응답 — otpRequired=false 면 access 토큰(refresh 없음, 세션은 access 수명까지), otpRequired=true 면 2단계 챌린지만 준다. "
    + "응답은 Cache-Control: no-store 다")
public record AdminLoginResponse(

    @Schema(description = "access 토큰(JWT, role=ADMIN). Authorization: Bearer 로 보낸다. otpRequired=true 면 null", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6ImF0K2p3dCJ9...",
        nullable = true)
    String accessToken,

    @Schema(description = "access 토큰의 남은 수명(초). 만료되면 다시 로그인한다. otpRequired=true 면 null", example = "1800", nullable = true)
    Long accessTokenExpiresIn,

    @Schema(description = "OTP 2단계가 필요한가 — true 면 토큰이 없고 challengeToken 으로 POST /api/v1/auth/admin/login/otp 를 부른다", example = "false")
    boolean otpRequired,

    @Schema(description = "2단계 챌린지(1회용, base64url 43자). otpRequired=false 면 null. 저장 · 로그하지 않고 2단계 요청 본문에만 싣는다",
        example = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg", nullable = true)
    String challengeToken,

    @Schema(description = "챌린지의 수명(초, 기본 300). 지나면 1단계부터 다시 한다. otpRequired=false 면 null", example = "300", nullable = true)
    Long challengeExpiresIn
) {

    /** 토큰 · 챌린지 원문이 로그 · 예외 메시지로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "AdminLoginResponse[accessToken=" + mask(accessToken) + ", accessTokenExpiresIn=" + accessTokenExpiresIn + ", otpRequired=" + otpRequired
            + ", challengeToken=" + mask(challengeToken) + ", challengeExpiresIn=" + challengeExpiresIn + "]";
    }

    private static String mask(String value) {
        return value == null ? "null" : "****";
    }
}
