package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item;

import com.commercefoundry.security.principal.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 운영자 계정 목록의 한 행 (#236). 이메일 · 이름은 <b>마스킹한 값</b>이다 — 회원 목록({@link AdminMemberItem})과 같은 규칙이다.
 */
@Builder
@Schema(description = "운영자 계정 목록 항목 — 이메일 · 이름은 마스킹한 값이다")
public record AdminAccountItem(

    @Schema(description = "운영자 회원 아이디 (문자열)", example = "4611686018427387904")
    String memberId,

    @Schema(description = "이메일 — 아이디 앞 2자(2자면 1자, 1자면 0자)와 도메인 첫 글자 · 최상위 도메인만 남긴다", example = "st***@e***.com")
    String email,

    @Schema(description = "이름 — 첫 글자 · 마지막 글자만 남긴다(2자면 첫 글자만)", example = "김*영")
    String name,

    @Schema(description = "역할 — ADMIN(이행 중인 기존 대표 관리자) · OWNER · MANAGER · STAFF", example = "MANAGER")
    MemberRole role,

    @Schema(description = "사용 중인가. false 면 로그인 · 어드민 API 를 쓸 수 없다", example = "true")
    boolean active,

    @Schema(description = "비활성화 시각 (ISO-8601 UTC). 사용 중이면 null", example = "2026-11-03T03:00:00Z", nullable = true)
    Instant deactivatedAt,

    @Schema(description = "OTP 등록 여부", example = "false")
    boolean otpEnrolled,

    @Schema(description = "계정 생성 시각 (ISO-8601 UTC)", example = "2026-10-18T03:00:00Z")
    Instant createdAt
) {

}
