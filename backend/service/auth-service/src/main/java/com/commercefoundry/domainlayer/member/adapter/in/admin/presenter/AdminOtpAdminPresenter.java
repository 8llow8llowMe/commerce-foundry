package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpEnrollmentResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpRecoveryCodesResponse;
import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response.AdminOtpStatusResponse;
import com.commercefoundry.domainlayer.member.application.info.AdminOtpEnrollmentInfo;
import com.commercefoundry.domainlayer.member.application.info.AdminOtpRecoveryCodesInfo;
import com.commercefoundry.domainlayer.member.application.info.AdminOtpStatusInfo;
import org.springframework.stereotype.Component;

/** 관리자 본인 OTP (#238 PR-A2) Info → 어드민 응답. */
@Component
public class AdminOtpAdminPresenter {

    public AdminOtpStatusResponse toStatus(AdminOtpStatusInfo info) {
        return AdminOtpStatusResponse.builder()
            .enrolled(info.enrolled())
            .confirmedAt(info.confirmedAt())
            .remainingRecoveryCodes(info.remainingRecoveryCodes())
            .build();
    }

    public AdminOtpEnrollmentResponse toEnrollment(AdminOtpEnrollmentInfo info) {
        return AdminOtpEnrollmentResponse.builder()
            .secret(info.secret())
            .otpauthUri(info.otpauthUri())
            .expiresAt(info.expiresAt())
            .build();
    }

    public AdminOtpRecoveryCodesResponse toRecoveryCodes(AdminOtpRecoveryCodesInfo info) {
        return AdminOtpRecoveryCodesResponse.builder().recoveryCodes(info.recoveryCodes()).build();
    }
}
