package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.domain.model.SignUpAgreements;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

@Schema(description = "회원가입 약관 동의 — 세 칸 모두 보낸다. 약관 버전은 서버가 정한다")
public record SignUpAgreementsRequest(

    @Schema(description = "이용약관 동의 (필수 — true 여야 가입된다)", example = "true")
    @NotNull(message = MemberValidationMessage.TERMS_OF_SERVICE_REQUIRED)
    @AssertTrue(message = MemberValidationMessage.TERMS_OF_SERVICE_REQUIRED)
    Boolean termsOfService,

    @Schema(description = "개인정보 수집 · 이용 동의 (필수 — true 여야 가입된다)", example = "true")
    @NotNull(message = MemberValidationMessage.PRIVACY_POLICY_REQUIRED)
    @AssertTrue(message = MemberValidationMessage.PRIVACY_POLICY_REQUIRED)
    Boolean privacyPolicy,

    @Schema(description = "마케팅 정보 수신 동의 (선택 — false 도 이력으로 남는다)", example = "false")
    @NotNull(message = MemberValidationMessage.MARKETING_REQUIRED)
    Boolean marketing
) {

    /** 검증을 지난 뒤에만 부른다 — 필수 동의가 빠지면 도메인이 거절한다. */
    SignUpAgreements toDomain() {
        return new SignUpAgreements(termsOfService, privacyPolicy, marketing);
    }
}
