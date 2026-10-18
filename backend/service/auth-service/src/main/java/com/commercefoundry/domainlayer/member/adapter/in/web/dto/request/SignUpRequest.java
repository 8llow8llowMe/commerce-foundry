package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.command.SignUpCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청. 역할은 받지 않는다 — 언제나 고객(CUSTOMER)으로 가입한다")
public record SignUpRequest(

    @Schema(description = "로그인 이메일 — 254자 이하. 앞뒤 공백을 지우고 소문자로 저장한다", example = "customer@example.com")
    @NotBlank(message = MemberValidationMessage.EMAIL_REQUIRED)
    @Size(max = Member.EMAIL_MAX_LENGTH, message = MemberValidationMessage.EMAIL_LENGTH_INVALID)
    @Email(regexp = MemberRequestPatterns.EMAIL, message = MemberValidationMessage.EMAIL_FORMAT_INVALID)
    String email,

    @Schema(description = "비밀번호 — 8~64자. 영문 · 숫자를 1자 이상씩 포함하고, 공백 없이 영문 · 숫자 · 특수문자(ASCII)만", example = "mokoyuri2026!")
    @NotBlank(message = MemberValidationMessage.PASSWORD_REQUIRED)
    @Size(min = 8, max = 64, message = MemberValidationMessage.PASSWORD_LENGTH_INVALID)
    @Pattern(regexp = MemberRequestPatterns.PASSWORD, message = MemberValidationMessage.PASSWORD_FORMAT_INVALID)
    String password,

    @Schema(description = "이름 — 1~50자. 앞뒤 공백을 지우고 저장한다", example = "홍길동")
    @NotBlank(message = MemberValidationMessage.NAME_REQUIRED)
    @Size(max = Member.NAME_MAX_LENGTH, message = MemberValidationMessage.NAME_LENGTH_INVALID)
    String name,

    @Schema(description = "휴대폰 번호 (선택) — 01012345678 · 010-1234-5678. 숫자만 저장한다", example = "010-1234-5678", nullable = true)
    @Pattern(regexp = MemberRequestPatterns.PHONE, message = MemberValidationMessage.PHONE_FORMAT_INVALID)
    String phone,

    @Schema(description = "약관 동의")
    @NotNull(message = MemberValidationMessage.AGREEMENTS_REQUIRED)
    @Valid
    SignUpAgreementsRequest agreements
) {

    public SignUpCommand toCommand() {
        return new SignUpCommand(email, password, name, phone, agreements.toDomain());
    }

    /** 비밀번호 원문 · 개인정보를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "SignUpRequest[agreements=" + agreements + "]";
    }
}
