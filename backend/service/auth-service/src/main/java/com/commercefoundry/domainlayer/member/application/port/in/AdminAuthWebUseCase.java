package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;

/**
 * 관리자 로그인 (#6). 경로는 {@code /api/v1/auth/admin/login} 이다 — {@code /api/v1/admin/**} 는 게이트웨이 · 서비스 URL 규칙이 ADMIN 토큰을 요구해
 * 토큰 없이 부를 수 없다 ({@code api-design-guide.md} §2-4).
 */
public interface AdminAuthWebUseCase {

    /** 이메일 · 비밀번호 로그인 — access 토큰만 준다. 실패는 이유와 무관하게 {@code AUTH_030}(400) 이다. */
    AdminLoginResponse login(AdminLoginCommand command);
}
