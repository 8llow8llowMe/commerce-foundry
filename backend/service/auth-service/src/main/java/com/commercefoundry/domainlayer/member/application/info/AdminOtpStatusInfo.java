package com.commercefoundry.domainlayer.member.application.info;

import java.time.Instant;

/**
 * 로그인한 관리자의 OTP 상태 (#238 PR-A2).
 *
 * @param enrolled               확정된 OTP 가 있는가. 진행 중인(확인 전) 등록은 등록이 아니다
 * @param confirmedAt            등록을 확인한 시각. 없으면 null
 * @param remainingRecoveryCodes 아직 쓰지 않은 복구 코드 수. 미등록이면 0
 */
public record AdminOtpStatusInfo(boolean enrolled, Instant confirmedAt, long remainingRecoveryCodes) {
}
