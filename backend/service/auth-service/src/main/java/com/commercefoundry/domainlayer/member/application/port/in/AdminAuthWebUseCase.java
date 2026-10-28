package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;

/**
 * 관리자 로그인 (#6). 경로는 {@code /api/v1/auth/admin/login} 이다 — {@code /api/v1/admin/**} 는 게이트웨이 · 서비스 URL 규칙이 ADMIN 토큰을 요구해
 * 토큰 없이 부를 수 없다 ({@code api-design-guide.md} §2-4).
 */
public interface AdminAuthWebUseCase {

    /**
     * 이메일 · 비밀번호 로그인 — access 토큰만 준다. 실패는 이유와 무관하게 {@code AUTH_030}(400) 이다.
     * 같은 이메일의 시도가 허용 횟수를 넘으면 비밀번호가 맞아도 {@code AUTH_040}(429, {@code LoginAttemptLimitedException}) 이다 (#66 — 고객 로그인과 따로 센다).
     * 시도 계수 저장소 장애는 {@code AUTH_013}(503) 이다(fail-closed).
     */
    AdminLoginResponse login(AdminLoginCommand command);
}
