package com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 작업 상세 · 취소 응답 — 목록 항목과 같은 운영자 칸이다 ({@code api-design-guide.md} §2-8, #305). 화면이 진행 중인 작업 하나를 다시 읽을 때 쓴다.
 * 작업 실패도 200 이다 — 상태 {@code FAILED} 와 안내 문구로 알린다. 결과 파일 링크는 결과 파일(#212 PR-E)이 생기면 더한다.
 */
@Builder
@Schema(description = "어드민 작업 상세")
public record JobAdminResponse(

    @Schema(description = "작업 아이디", example = "4611686018427387904")
    String jobId,

    @Schema(description = "작업 종류 코드", example = "SALES_REPORT_EXPORT")
    String type,

    @Schema(description = "작업 이름", example = "월 매출 리포트 내보내기")
    String typeName,

    @Schema(
        description = "작업 상태 — QUEUED(대기) · RUNNING(실행 중) · SUCCEEDED(성공) · FAILED(실패) · CANCELLED(취소)",
        example = "{\"code\": \"FAILED\", \"name\": \"실패\", \"description\": \"재시도 상한에 닿았거나 다시 시도할 수 없는 오류로 끝났습니다.\"}"
    )
    CodeNameDescriptionMetadata status,

    @Schema(description = "진행률 0 ~ 100. 아직 알 수 없으면 null", example = "100", nullable = true)
    Integer progressPercent,

    @Schema(description = "진행 문구. 없으면 null", example = "10/10 구간 집계 완료", nullable = true)
    String progressMessage,

    @Schema(description = "운영자 안내 문구 — 실패 이유 · 재시도 대기 · 취소 요청됨. 알릴 것이 없으면 null",
        example = "작업이 너무 오래 걸려 중단했습니다. 범위를 줄여 다시 요청해 주세요.", nullable = true)
    String notice,

    @Schema(description = "지금 취소할 수 있다", example = "false")
    boolean cancellable,

    @Schema(description = "요청 시각 (UTC, ISO-8601)", example = "2026-10-24T03:00:00Z")
    Instant requestedAt,

    @Schema(description = "끝난 시각 (성공 · 실패 · 취소일 때만)", example = "2026-10-24T03:31:00Z", nullable = true)
    Instant finishedAt
) {
}
