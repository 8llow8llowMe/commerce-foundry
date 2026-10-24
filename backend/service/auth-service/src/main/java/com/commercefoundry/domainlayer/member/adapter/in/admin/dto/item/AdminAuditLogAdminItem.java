package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 어드민 감사 로그 한 행 (#192). 값은 {@code admin.audit} 로그 한 줄과 같다 — 개인정보는 없다. */
@Builder
@Schema(description = "관리자 감사 로그 항목")
public record AdminAuditLogAdminItem(

    @Schema(description = "감사 로그 아이디", example = "4611686018427387905")
    String auditLogId,

    @Schema(description = "행위 — 로그 검색 키와 같은 이름", allowableValues = {"ADMIN_LOGIN", "MEMBER_SEARCH"}, example = "ADMIN_LOGIN")
    String action,

    @Schema(description = "행위한 관리자 회원 아이디. 로그인 실패처럼 모르면 null", example = "4611686018427387904", nullable = true)
    String actorMemberId,

    @Schema(description = "대상 — member:<id> · members?role=..&page=..&size=... 없으면 null", example = "member:4611686018427387904", nullable = true)
    String target,

    @Schema(description = "결과 — 실패 이유는 나누지 않는다", allowableValues = {"SUCCESS", "FAILURE"}, example = "FAILURE")
    String result,

    @Schema(description = "행위 시각 (UTC, ISO-8601)", example = "2026-10-24T03:00:00Z")
    Instant createdAt
) {
}
