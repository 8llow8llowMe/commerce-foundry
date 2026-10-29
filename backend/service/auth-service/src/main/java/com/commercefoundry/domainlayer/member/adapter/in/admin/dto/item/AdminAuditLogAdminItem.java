package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 어드민 감사 로그 한 행 (#192). 저장된 칸은 {@code admin.audit} 로그와 같고, 표시 이름은 조회 시 마스킹해 붙인다 (#322). */
@Builder
@Schema(description = "관리자 감사 로그 항목")
public record AdminAuditLogAdminItem(

    @Schema(description = "감사 로그 아이디", example = "4611686018427387905")
    String auditLogId,

    @Schema(description = "행위 — 로그 검색 키와 같은 이름", allowableValues = {
        "ADMIN_LOGIN", "MEMBER_SEARCH", "ADMIN_CREATE", "ADMIN_DEACTIVATE", "ADMIN_PASSWORD_CHANGE",
        "ADMIN_OTP_ENROLL", "ADMIN_OTP_DISABLE", "ADMIN_OTP_RECOVERY_REGENERATE"
    }, example = "ADMIN_LOGIN")
    String action,

    @Schema(description = "행위한 관리자 회원 아이디. 로그인 실패처럼 모르면 null", example = "4611686018427387904", nullable = true)
    String actorMemberId,

    @Schema(description = "행위한 관리자의 마스킹 이름. 회원을 모르거나 이름이 없으면 null", example = "홍*동", nullable = true)
    String actorDisplayName,

    @Schema(description = "대상 — member:<id> · members?role=..&page=..&size=... 없으면 null", example = "member:4611686018427387904", nullable = true)
    String target,

    @Schema(description = "대상이 member:<id> 일 때 그 회원의 마스킹 이름. 그 외 대상이거나 모르면 null", example = "김*수", nullable = true)
    String targetDisplayName,

    @Schema(description = "결과 — 실패 이유는 나누지 않는다", allowableValues = {"SUCCESS", "FAILURE"}, example = "FAILURE")
    String result,

    @Schema(description = "행위 시각 (UTC, ISO-8601)", example = "2026-10-24T03:00:00Z")
    Instant createdAt
) {
}
