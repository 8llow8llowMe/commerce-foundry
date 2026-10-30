package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.AdminLoginResponse;
import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.command.AdminOtpLoginCommand;

/**
 * 관리자 로그인 (#6 · #238 PR-B). 경로는 {@code /api/v1/auth/admin/login} · {@code …/login/otp} 이다 — {@code /api/v1/admin/**} 는 게이트웨이 · 서비스 URL 규칙이
 * ADMIN 토큰을 요구해 토큰 없이 부를 수 없다 ({@code api-design-guide.md} §2-4).
 */
public interface AdminAuthWebUseCase {

    /**
     * 이메일 · 비밀번호 로그인(1단계). OTP 를 등록하지 않은 관리자는 access 토큰을 받는다({@code otpRequired=false}).
     * 등록한 관리자는 access 토큰 대신 2단계 챌린지를 받는다({@code otpRequired=true}) — 비밀번호만으로는 토큰을 받지 못한다.
     * 실패는 이유와 무관하게 {@code AUTH_030}(400) 이다. 같은 이메일의 시도가 허용 횟수를 넘으면 비밀번호가 맞아도 {@code AUTH_040}(429,
     * {@code LoginAttemptLimitedException}) 이다 (#66 — 고객 로그인과 따로 센다). 시도 계수 · 챌린지 저장소 장애는 {@code AUTH_013}(503) 이다(fail-closed).
     */
    AdminLoginResponse login(AdminLoginCommand command);

    /**
     * 로그인 2단계 — 1단계의 챌린지와 인증 앱 코드(또는 복구 코드)로 access 토큰을 받는다 (#238 PR-B). 챌린지 없음 · 만료 · 이미 씀 · 그 사이 관리자 비활성화 ·
     * OTP 해제는 {@code AUTH_034}(400), 코드가 틀리거나 이미 쓴 코드면 {@code AUTH_035}(400), 같은 관리자의 2단계 시도가 허용 횟수를 넘으면 {@code AUTH_043}(429),
     * 같은 관리자의 다른 OTP 쓰기와 겹치면 {@code AUTH_044}(409), 저장소 장애는 {@code AUTH_013} · 비밀 복호화 실패는 {@code AUTH_039}(503) 이다.
     */
    AdminLoginResponse loginWithOtp(AdminOtpLoginCommand command);
}
