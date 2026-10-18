package com.commercefoundry.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "내 정보 응답")
public record MemberProfileResponse(

    @Schema(description = "회원 아이디", example = "4611686018427387904")
    String memberId,

    @Schema(description = "로그인 이메일 — 소문자로 정규화한 값", example = "customer@example.com")
    String email,

    @Schema(description = "이름", example = "홍길동")
    String name,

    @Schema(description = "휴대폰 번호 — 숫자만. 없으면 null", example = "01012345678", nullable = true)
    String phone,

    @Schema(description = "마케팅 정보 수신 동의 여부", example = "false")
    boolean marketingAgreed
) {

    /** 개인정보(이메일 · 이름 · 전화)를 싣지 않는다 — 로그 · 예외 메시지로 새지 않게 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "MemberProfileResponse[memberId=" + memberId + ", marketingAgreed=" + marketingAgreed + "]";
    }
}
