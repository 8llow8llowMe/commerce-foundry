package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item;

import com.commercefoundry.security.principal.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 회원 목록의 한 행 (#6). 이메일 · 이름 · 전화는 <b>마스킹한 값</b>이다 — 목록 화면에 원문이 필요 없고, 화면 캡처 · 로그로 개인정보가 새지 않게.
 */
@Builder
@Schema(description = "어드민 회원 목록 항목 — 개인정보는 마스킹한 값이다")
public record AdminMemberItem(

    @Schema(description = "회원 아이디 (문자열)", example = "4611686018427387904")
    String memberId,

    @Schema(description = "이메일 — 아이디 앞 2자(2자면 1자, 1자면 0자)와 도메인 첫 글자 · 최상위 도메인만 남긴다", example = "ad***@e***.com")
    String email,

    @Schema(description = "이름 — 첫 글자 · 마지막 글자만 남긴다(2자면 첫 글자만)", example = "홍*동")
    String name,

    @Schema(description = "휴대폰 번호 — 가운데를 가린다. 없으면 null", example = "010-****-5678", nullable = true)
    String phone,

    @Schema(description = "역할", example = "CUSTOMER")
    MemberRole role,

    @Schema(description = "가입 시각 (ISO-8601 UTC)", example = "2026-10-18T03:00:00Z")
    Instant createdAt
) {

}
