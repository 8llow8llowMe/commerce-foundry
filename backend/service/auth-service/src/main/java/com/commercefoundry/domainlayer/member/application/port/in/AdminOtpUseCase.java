package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpEnrollmentResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpStatusResponse;

/**
 * 로그인한 관리자 본인의 OTP (#238 PR-A2). 행위자는 access 토큰의 memberId 만 쓴다 — 다른 관리자의 OTP 는 여기서 다루지 않는다(동료 초기화는 PR-C).
 */
public interface AdminOtpUseCase {

    AdminOtpStatusResponse getStatus(long adminMemberId);

    /** 등록을 시작한다 — 비밀과 인증 앱 등록 URI 를 이번 한 번만 내려준다. */
    AdminOtpEnrollmentResponse startEnrollment(long adminMemberId);

    /** 진행 중인 등록을 비밀번호 + 인증 앱 코드로 확인한다. 복구 코드 원문은 이번 한 번만 내려준다. */
    AdminOtpRecoveryCodesResponse confirmEnrollment(long adminMemberId, String currentPassword, String code);

    /** 비밀번호 + (TOTP 또는 복구 코드)로 OTP 를 해제한다. */
    void disable(long adminMemberId, String currentPassword, String code);

    /** 비밀번호 + (TOTP 또는 복구 코드)로 복구 코드를 다시 발급한다. 이전 코드는 모두 무효다. */
    AdminOtpRecoveryCodesResponse regenerateRecoveryCodes(long adminMemberId, String currentPassword, String code);
}
