package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.request.AdminRequestPatterns;
import com.commercefoundry.domainlayer.member.application.command.AdminOtpLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관리자 로그인 2단계 요청 (#238 PR-B). 코드 형식은 어드민 재인증(해제 · 재발급)과 같은 {@link AdminRequestPatterns#OTP_CODE} 다 — 6자리 또는 복구 코드.
 * 챌린지는 비었을 때만 필드 검증(153)이고, 모양이 달라도 만료와 같은 {@code AUTH_034} 다 — 모양 · 만료 · 이미 씀을 나누지 않는다.
 */
@Schema(description = "관리자 로그인 2단계 요청 — 1단계의 challengeToken 과 인증 앱 코드 또는 복구 코드 하나")
public record AdminOtpLoginRequest(

    @Schema(description = "1단계 응답의 challengeToken", example = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg")
    @NotBlank(message = MemberValidationMessage.ADMIN_OTP_CHALLENGE_REQUIRED)
    String challengeToken,

    @Schema(description = "인증 앱의 6자리 코드 또는 복구 코드(xxxxx-xxxxx, 소문자 · 하이픈 생략 가능). 쓴 코드는 다시 쓸 수 없다", example = "123456")
    @NotBlank(message = MemberValidationMessage.ADMIN_OTP_CODE_REQUIRED)
    @Size(max = AdminRequestPatterns.OTP_CODE_MAX_LENGTH, message = MemberValidationMessage.ADMIN_OTP_CODE_FORMAT)
    @Pattern(regexp = AdminRequestPatterns.OTP_CODE, message = MemberValidationMessage.ADMIN_OTP_CODE_FORMAT)
    String code
) {

    public AdminOtpLoginCommand toCommand() {
        return new AdminOtpLoginCommand(challengeToken, code);
    }

    /** 챌린지 · 코드를 가린다. */
    @Override
    public String toString() {
        return "AdminOtpLoginRequest[challengeToken=****, code=****]";
    }
}
