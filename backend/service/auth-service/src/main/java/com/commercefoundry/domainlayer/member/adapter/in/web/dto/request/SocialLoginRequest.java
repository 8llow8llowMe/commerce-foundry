package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.command.SocialLoginCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "소셜 인가 코드 교환 — 신규 가입이면 약관 동의를 함께 보낸다")
public record SocialLoginRequest(
    @Schema(description = "제공자 콜백의 인가 코드")
    @NotBlank(message = MemberValidationMessage.SOCIAL_CODE_INVALID)
    @Size(max = 2048, message = MemberValidationMessage.SOCIAL_CODE_INVALID)
    String code,

    @Schema(description = "authorize-url 응답의 state — 콜백과 sessionStorage 값을 비교한다")
    @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = MemberValidationMessage.SOCIAL_STATE_INVALID)
    @NotBlank(message = MemberValidationMessage.SOCIAL_STATE_INVALID)
    String state,

    @Schema(description = "같은 브라우저 sessionStorage에 보관한 verifier. 제공자 URL에 넣지 않는다")
    @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = MemberValidationMessage.SOCIAL_VERIFIER_INVALID)
    @NotBlank(message = MemberValidationMessage.SOCIAL_VERIFIER_INVALID)
    String verifier,

    @Schema(description = "신규 가입에만 필수인 약관 동의")
    @Valid
    SignUpAgreementsRequest agreements
) {
    public SocialLoginCommand toCommand() {
        return new SocialLoginCommand(code, state, verifier, agreements == null ? null : agreements.toDomain());
    }

    @Override
    public String toString() {
        return "SocialLoginRequest[redacted]";
    }
}