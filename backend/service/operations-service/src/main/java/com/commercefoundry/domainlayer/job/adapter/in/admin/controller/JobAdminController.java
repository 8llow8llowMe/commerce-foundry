package com.commercefoundry.domainlayer.job.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobValidationMessage;
import com.commercefoundry.domainlayer.job.application.model.JobStatusFilter;
import com.commercefoundry.domainlayer.job.application.port.in.JobAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 백그라운드 작업 — 목록 · 상세 · 취소 (#212 PR-C). 범용 등록 API 는 두지 않는다 — 각 기능 어드민 API 가 작업을 등록하고 {@code 202 + jobId} 로 답하면
 * 화면이 이 API 로 상태를 본다 ({@code api-design-guide.md} §7). 취소한 어드민은 access 토큰의 memberId 로만 남긴다 — 본문으로 받지 않는다.
 *
 * <p>파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 백그라운드 작업", description = "리포트 · 집계 · AI 생성 같은 오래 걸리는 작업의 상태 조회 · 취소")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/jobs")
public class JobAdminController {

    private final JobAdminUseCase jobAdminUseCase;

    @Operation(
        summary = "작업 목록 조회",
        description = "등록 최신순(아이디 내림차순)으로 페이지 단위로 읽는다. 상태 · 종류로 거를 수 있고, 없으면 전체다. 조건에 맞는 작업이 없으면 빈 목록이다. "
            + "입력 · 결과 JSON 은 상세에서 본다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<JobAdminItem>>> getJobs(
        @Parameter(description = "상태. 없으면 전체") @RequestParam(required = false) JobStatusFilter status,
        @Parameter(description = "작업 종류 (영문 대문자 · 숫자 · 밑줄 1 ~ 64자). 없으면 전체", example = "SALES_REPORT_EXPORT")
        @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{1,64}", message = JobValidationMessage.TYPE_PATTERN) String type,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = JobValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = JobValidationMessage.SIZE_RANGE) @Max(value = 100, message = JobValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(jobAdminUseCase.getJobs(new JobAdminSearchCommand(status, type, page, size))));
    }

    @Operation(
        summary = "작업 상세 조회",
        description = "상태 · 진행률 · 시도 · 입력 · 결과 · 오류를 본다. 작업이 실패해도 200 이고 status 가 FAILED, errorCode 가 이유다. 없는 작업이면 404(JOB_001)다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{jobId}")
    public ResponseEntity<Response<JobAdminResponse>> getJob(
        @Parameter(description = "작업 아이디") @PathVariable @Positive(message = JobValidationMessage.JOB_ID_POSITIVE) long jobId
    ) {
        return ResponseEntity.ok().body(Response.success(jobAdminUseCase.getJob(jobId)));
    }

    @Operation(
        summary = "작업 취소",
        description = "대기(QUEUED) 중이면 바로 CANCELLED 로 끝낸다. 실행(RUNNING) 중이면 취소 요청만 남기고 응답한다 — 핸들러가 다음 진행 보고에서 멈추면 "
            + "CANCELLED 가 되고, 그 전에 끝나면 그 결과로 끝난다. 이미 취소를 요청한 실행 중 작업에 다시 보내도 같은 응답이다(멱등). "
            + "이미 끝난 작업이면 409(JOB_022), 없는 작업이면 404(JOB_001)다. 워커와 계속 겹쳐 반영하지 못하면 409(JOB_021) — 다시 시도한다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<Response<JobAdminResponse>> cancel(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "작업 아이디") @PathVariable @Positive(message = JobValidationMessage.JOB_ID_POSITIVE) long jobId
    ) {
        return ResponseEntity.ok().body(Response.success(jobAdminUseCase.cancel(jobId, member.memberId())));
    }
}
