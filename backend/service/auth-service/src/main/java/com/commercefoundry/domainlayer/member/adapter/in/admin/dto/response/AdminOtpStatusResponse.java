package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
@Schema(description = "로그인한 관리자의 OTP 상태 — 내 계정 화면용")
public record AdminOtpStatusResponse(

    @Schema(description = "OTP 를 등록(확인까지 끝)했는가. 확인 전 진행 중인 등록은 false 다", example = "true")
    boolean enrolled,

    @Schema(description = "등록을 확인한 시각 (UTC, ISO-8601). 미등록이면 null", example = "2026-10-29T03:00:00Z", nullable = true)
    Instant confirmedAt,

    @Schema(description = "아직 쓰지 않은 복구 코드 수. 미등록이면 0", example = "8")
    long remainingRecoveryCodes
) {
}
