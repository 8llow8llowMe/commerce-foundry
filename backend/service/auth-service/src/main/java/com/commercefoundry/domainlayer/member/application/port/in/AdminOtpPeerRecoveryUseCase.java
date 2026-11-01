package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;

/** 다른 활성 관리자의 OTP 자격을 새 자격·복구 코드로 교체한다 (#238 PR-C). */
public interface AdminOtpPeerRecoveryUseCase {

    AdminOtpRecoveryCodesResponse recover(long actorMemberId, long targetMemberId, String currentPassword, String code);
}
