package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.command.LoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 고객 로그인 요청. 비밀번호 형식(가입 규칙)은 보지 않는다 — 형식이 틀린 비밀번호도 "틀린 비밀번호"(AUTH_001)로 답해 실패 이유를 나누지 않는다.
 */
@Schema(description = "고객 로그인 요청")
public record LoginRequest(

    @Schema(description = "로그인 이메일 — 대소문자를 가리지 않는다", example = "customer@example.com")
    @NotBlank(message = MemberValidationMessage.EMAIL_REQUIRED)
    @Size(max = Member.EMAIL_MAX_LENGTH, message = MemberValidationMessage.EMAIL_LENGTH_INVALID)
    String email,

    @Schema(description = "비밀번호", example = "mokoyuri2026!")
    @NotBlank(message = MemberValidationMessage.PASSWORD_REQUIRED)
    String password
) {

    public LoginCommand toCommand() {
        return new LoginCommand(email, password);
    }

    /** 이메일 · 비밀번호 원문을 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
