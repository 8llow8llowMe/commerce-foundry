package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AuthTokenResponse;
import com.commercefoundry.domainlayer.member.application.command.LoginCommand;

/**
 * 고객 로그인 · 토큰 재발급 · 로그아웃 (#4 A3 · A4). 토큰 저장소(Redis) 장애는 모두 {@code AUTH_013}(503) 다.
 */
public interface AuthWebUseCase {

    /**
     * 이메일 · 비밀번호 로그인. 실패는 이유와 무관하게 {@code AUTH_001}(400) 이다.
     * 같은 이메일의 시도가 허용 횟수를 넘으면 비밀번호가 맞아도 {@code AUTH_040}(429, {@code LoginAttemptLimitedException}) 이다 (#66).
     * 토큰 · 시도 계수 저장소 장애는 {@code AUTH_013}(503) 이다(fail-closed).
     */
    AuthTokenResponse login(LoginCommand command);

    /** refresh 토큰 회전. 형식 오류 {@code AUTH_010} · 무효 {@code AUTH_011} · 재사용 감지 {@code AUTH_012} 는 모두 401 이다. */
    AuthTokenResponse reissue(String refreshToken);

    /**
     * 로그아웃 — 이 회원의 refresh family 를 폐기하고 access 토큰을 차단 목록에 넣는다. 모르는 refresh 토큰이어도 성공이다(멱등).
     *
     * @param tokenId      로그아웃하는 access 토큰의 {@code jti}
     * @param refreshToken 없거나 모양이 틀리면 family 폐기를 건너뛴다
     */
    void logout(long memberId, String tokenId, String refreshToken);
}
