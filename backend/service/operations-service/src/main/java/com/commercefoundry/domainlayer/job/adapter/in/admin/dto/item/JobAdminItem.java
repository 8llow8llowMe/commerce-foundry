package com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/**
 * 어드민 작업 목록 한 행. 입력 · 결과 JSON 은 싣지 않는다 — 상세({@code JobAdminResponse})에서 본다.
 */
@Builder
@Schema(description = "어드민 작업 목록 항목")
public record JobAdminItem(

    @Schema(description = "작업 아이디", example = "4611686018427387904")
    String jobId,

    @Schema(description = "작업 종류 — 핸들러 이름", example = "SALES_REPORT_EXPORT")
    String type,

    @Schema(
        description = "작업 상태 — QUEUED(대기) · RUNNING(실행 중) · SUCCEEDED(성공) · FAILED(실패) · CANCELLED(취소)",
        example = "{\"code\": \"RUNNING\", \"name\": \"실행 중\", \"description\": \"워커가 가져가 실행하고 있습니다.\"}"
    )
    CodeNameDescriptionMetadata status,

    @Schema(description = "진행률 0 ~ 100. 핸들러가 보고하기 전이면 null", example = "40", nullable = true)
    Integer progressPercent,

    @Schema(description = "가져간 시도 수 (0 부터). RUNNING 이면 실행 중인 시도 번호", example = "1")
    int attempt,

    @Schema(description = "시도 횟수 상한 (첫 실행 포함)", example = "3")
    int maxAttempts,

    @Schema(description = "취소 요청을 받았다 — RUNNING 이면 핸들러가 다음 진행 보고에서 멈춘다", example = "false")
    boolean cancelRequested,

    @Schema(description = "마지막 실패 코드 — 재시도 대기 중에도 남고 성공하면 비운다. 없으면 null", example = "UPSTREAM_TIMEOUT", nullable = true)
    String errorCode,

    @Schema(description = "등록한 어드민 회원 아이디. 시스템 등록이면 null", example = "4611686018427387903", nullable = true)
    String requestedByMemberId,

    @Schema(description = "이 시각부터 가져갈 수 있다 — 재시도면 백오프가 더해진 시각 (UTC, ISO-8601)", example = "2026-10-24T03:00:00Z")
    Instant scheduledAt,

    @Schema(description = "이번 시도를 가져간 시각. 아직 가져가지 않았으면 null", example = "2026-10-24T03:00:02Z", nullable = true)
    Instant startedAt,

    @Schema(description = "종료 시각 (SUCCEEDED · FAILED · CANCELLED 일 때만)", example = "2026-10-24T03:01:10Z", nullable = true)
    Instant finishedAt
) {
}
