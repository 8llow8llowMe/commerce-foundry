package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "소셜 계정 연결 완료")
public record SocialAccountLinkRequest(
    @Schema(description = "제공자 인가 코드")
    @NotBlank(message = MemberValidationMessage.SOCIAL_CODE_INVALID)
    @Size(max = 2048, message = MemberValidationMessage.SOCIAL_CODE_INVALID)
    String code,

    @Schema(description = "연결용 state")
    @NotBlank(message = MemberValidationMessage.SOCIAL_STATE_INVALID)
    @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = MemberValidationMessage.SOCIAL_STATE_INVALID)
    String state,

    @Schema(description = "같은 탭에 보관한 verifier")
    @NotBlank(message = MemberValidationMessage.SOCIAL_VERIFIER_INVALID)
    @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = MemberValidationMessage.SOCIAL_VERIFIER_INVALID)
    String verifier
) {
    @Override
    public String toString() { return "SocialAccountLinkRequest[redacted]"; }
}
