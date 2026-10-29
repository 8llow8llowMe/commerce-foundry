package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
@Schema(description = "시작한 OTP 등록. 비밀은 이 응답에서 한 번만 내려준다 — 저장 · 로그하지 말고 인증 앱 등록에만 쓴다")
public record AdminOtpEnrollmentResponse(

    @Schema(description = "TOTP 비밀 (Base32, 대문자 · 패딩 없음 32자). QR 을 읽지 못할 때 직접 입력한다", example = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP")
    String secret,

    @Schema(description = "인증 앱 등록 URI — FE 가 QR 로 그린다. 비밀이 들어 있다",
        example = "otpauth://totp/Commerce%20Foundry%20Admin:owner%40example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
            + "&issuer=Commerce%20Foundry%20Admin&algorithm=SHA1&digits=6&period=30")
    String otpauthUri,

    @Schema(description = "이 시각까지 확인해야 한다 (UTC, ISO-8601). 지나면 등록을 다시 시작한다", example = "2026-10-29T03:15:00Z")
    Instant expiresAt
) {

    /** 비밀 · URI 를 가린다. */
    @Override
    public String toString() {
        return "AdminOtpEnrollmentResponse[secret=****, otpauthUri=****, expiresAt=" + expiresAt + "]";
    }
}
