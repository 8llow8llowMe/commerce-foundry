package com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 작업 목록 한 행 — 쇼핑몰 운영자가 읽는 칸만 담는다 ({@code api-design-guide.md} §2-8, #305).
 * 시도 횟수 · 오류 코드 · 오류 원문 · 입력 · 결과 원문 · 워커 정보는 내리지 않는다.
 */
@Builder
@Schema(description = "어드민 작업 목록 항목")
public record JobAdminItem(

    @Schema(description = "작업 아이디", example = "4611686018427387904")
    String jobId,

    @Schema(description = "작업 종류 코드 — 목록 거르기(type)에 쓴다", example = "SALES_REPORT_EXPORT")
    String type,

    @Schema(description = "작업 이름", example = "월 매출 리포트 내보내기")
    String typeName,

    @Schema(
        description = "작업 상태 — QUEUED(대기) · RUNNING(실행 중) · SUCCEEDED(성공) · FAILED(실패) · CANCELLED(취소)",
        example = "{\"code\": \"RUNNING\", \"name\": \"실행 중\", \"description\": \"워커가 가져가 실행하고 있습니다.\"}"
    )
    CodeNameDescriptionMetadata status,

    @Schema(description = "진행률 0 ~ 100. 아직 알 수 없으면 null", example = "40", nullable = true)
    Integer progressPercent,

    @Schema(description = "진행 문구. 없으면 null", example = "3/10 구간 집계 중", nullable = true)
    String progressMessage,

    @Schema(description = "운영자 안내 문구 — 실패 이유 · 재시도 대기 · 취소 요청됨. 알릴 것이 없으면 null", example = "일시적인 오류로 잠시 뒤 다시 시도합니다.", nullable = true)
    String notice,

    @Schema(description = "지금 취소할 수 있다 — 대기 · 실행 중이고 아직 취소를 요청하지 않았다", example = "true")
    boolean cancellable,

    @Schema(description = "요청 시각 (UTC, ISO-8601)", example = "2026-10-24T03:00:00Z")
    Instant requestedAt,

    @Schema(description = "끝난 시각 (성공 · 실패 · 취소일 때만)", example = "2026-10-24T03:01:10Z", nullable = true)
    Instant finishedAt
) {
}
