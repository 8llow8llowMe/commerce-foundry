package com.commercefoundry.domainlayer.member.adapter.in.web.dto.request;

import com.commercefoundry.domainlayer.member.application.command.MemberProfileUpdateCommand;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "내 정보 부분 수정 — 보낸 칸만 바꾼다. 하나도 보내지 않으면 400(AUTH_115)")
public record MemberProfileUpdateRequest(

    @Schema(description = "이름 — 1~50자. 없으면 그대로", example = "홍길동", nullable = true)
    @Size(min = 1, max = Member.NAME_MAX_LENGTH, message = MemberValidationMessage.NAME_LENGTH_INVALID)
    @Pattern(regexp = MemberRequestPatterns.NOT_BLANK, message = MemberValidationMessage.NAME_BLANK)
    String name,

    @Schema(description = "휴대폰 번호 — 01012345678 · 010-1234-5678. 없으면 그대로, 빈 문자열이면 지운다", example = "010-1234-5678", nullable = true)
    @Pattern(regexp = MemberRequestPatterns.PHONE_OR_EMPTY, message = MemberValidationMessage.PHONE_FORMAT_INVALID)
    String phone,

    @Schema(description = "마케팅 정보 수신 동의. 없으면 그대로, 바뀌면 동의 이력을 한 줄 남긴다", example = "true", nullable = true)
    Boolean marketingAgreed
) {

    /** 칸이 모두 null 이면 거절한다. 오류 필드 이름은 {@code anyFieldPresent} 다. 요청 · 응답 JSON 과 문서에는 나오지 않는다. */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = MemberValidationMessage.PROFILE_UPDATE_EMPTY)
    public boolean isAnyFieldPresent() {
        return name != null || phone != null || marketingAgreed != null;
    }

    public MemberProfileUpdateCommand toCommand() {
        return new MemberProfileUpdateCommand(name, phone, marketingAgreed);
    }

    /** 개인정보(이름 · 전화)를 싣지 않는다. */
    @Override
    public String toString() {
        return "MemberProfileUpdateRequest[marketingAgreed=" + marketingAgreed + "]";
    }
}
