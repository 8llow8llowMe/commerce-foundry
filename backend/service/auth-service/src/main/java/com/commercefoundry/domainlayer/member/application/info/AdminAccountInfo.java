package com.commercefoundry.domainlayer.member.application.info;

import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;

/**
 * 운영자 계정 목록의 한 행 (#236). 이메일 · 이름은 원문이다 — 마스킹은 응답 모양의 일이라 Presenter 가 한다.
 *
 * @param deactivatedAt 비활성화 시각. 활성 운영자는 null
 * @param otpEnrolled OTP 를 등록했는가
 */
public record AdminAccountInfo(
    long memberId, String email, String name, MemberRole role, Instant deactivatedAt, boolean otpEnrolled, Instant createdAt
) {

    public boolean active() {
        return deactivatedAt == null;
    }

    /** 개인정보(이메일 · 이름)를 싣지 않는다. */
    @Override
    public String toString() {
        return "AdminAccountInfo[memberId=" + memberId + ", role=" + role + ", deactivatedAt=" + deactivatedAt + ", otpEnrolled=" + otpEnrolled + "]";
    }
}
