package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.command.AdminLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 로그인 요청 (#6). 고객 로그인 요청({@link LoginRequest})과 공유하지 않는다. 비밀번호 형식은 보지 않는다 — 틀리면 AUTH_030 이다.
 * 비밀번호 길이 상한(64자)도 필드 검증으로 두지 않는다 — 넘으면 "틀린 비밀번호" 와 같은 AUTH_030 이다({@code MemberLoginProcessor#authenticateAdmin}).
 * 필드 코드로 따로 답하면 실패 응답이 두 갈래가 된다.
 */
@Schema(description = "관리자 로그인 요청")
public record AdminLoginRequest(

    @Schema(description = "관리자 이메일 — 대소문자를 가리지 않는다", example = "admin@example.com")
    @NotBlank(message = MemberValidationMessage.ADMIN_EMAIL_REQUIRED)
    @Size(max = Member.EMAIL_MAX_LENGTH, message = MemberValidationMessage.ADMIN_EMAIL_LENGTH_INVALID)
    String email,

    @Schema(description = "비밀번호 — 64자를 넘으면 틀린 비밀번호와 같이 AUTH_030 이다", example = "change-me-admin-1")
    @NotBlank(message = MemberValidationMessage.ADMIN_PASSWORD_REQUIRED)
    String password
) {

    public AdminLoginCommand toCommand() {
        return new AdminLoginCommand(email, password);
    }

    /** 이메일 · 비밀번호 원문을 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "AdminLoginRequest[]";
    }
}
