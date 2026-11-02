package com.commercefoundry.domainlayer.job.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobResultDownloadResponse;
import com.commercefoundry.domainlayer.job.application.exception.JobValidationMessage;
import com.commercefoundry.domainlayer.job.application.port.in.JobResultDownloadUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.audit.AdminAudited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 백그라운드 작업")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/jobs")
public class JobResultDownloadController {
    private final JobResultDownloadUseCase useCase;

    @Operation(summary = "작업 결과 다운로드 URL 발급",
        description = "성공한 현재 시도의 비공개 결과만 내려준다. 파일 없음 JOB_004(404), 보관 만료 JOB_023(410), 저장소 장애 JOB_501(503). "
            + "URL은 최대 60초이며 저장·로그 수집하지 않는다. 실제 GET은 attachment·octet-stream·private,no-store다.")
    @AdminAudited(action = "JOB_RESULT_DOWNLOAD")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{jobId}/result-download")
    public ResponseEntity<Response<JobResultDownloadResponse>> download(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "작업 아이디") @PathVariable @Positive(message = JobValidationMessage.JOB_ID_POSITIVE) long jobId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.download(jobId, member.memberId())));
    }
}
