package com.commercefoundry.domainlayer.job.application.service.processor;

import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobSummaryInfo;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.query.JobSummaryQueryResult;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 어드민 작업 조회 — 목록 · 상세 (#212 PR-C, #305). 운영자에게 보이는 말로 바꾼다 ({@code api-design-guide.md} §2-8):
 * 작업 이름은 핸들러의 표시명, 실패 · 재시도 대기 · 취소 요청은 안내 문구다. 시도 횟수 · 오류 코드 · 오류 원문은 내보내지 않는다 —
 * 진단은 로그 · 대시보드의 몫이다. 트랜잭션은 부른 Facade 가 건다.
 */
@Component
@RequiredArgsConstructor
public class JobQueryProcessor {

    static final String NOTICE_FAILED = "작업을 끝내지 못했습니다. 다시 요청해 주세요. 계속 실패하면 관리자에게 문의해 주세요.";
    static final String NOTICE_RETRY_WAITING = "일시적인 오류로 잠시 뒤 다시 시도합니다.";
    static final String NOTICE_CANCEL_REQUESTED = "취소를 요청했습니다. 진행 중인 단계가 끝나면 멈춥니다.";

    /** 엔진이 붙이는 실패 코드의 안내 문구. 핸들러 고유 코드는 {@link JobHandler#failureNotice} 가 먼저 바꾼다. */
    static final Map<String, String> ENGINE_FAILURE_NOTICES = Map.of(
        Job.RUN_TIMEOUT, "작업이 너무 오래 걸려 중단했습니다. 범위를 줄여 다시 요청해 주세요.",
        Job.RESULT_TOO_LARGE, "결과가 너무 커서 저장하지 못했습니다. 범위를 줄여 다시 요청해 주세요.",
        Job.LEASE_EXPIRED, "일시적인 오류로 작업을 끝내지 못했습니다. 잠시 뒤 다시 요청해 주세요.",
        Job.HANDLER_ERROR, "일시적인 오류로 작업을 끝내지 못했습니다. 잠시 뒤 다시 요청해 주세요."
    );

    private final JobRepositoryPort jobRepositoryPort;
    private final JobHandlerProcessor jobHandlerProcessor;

    public Page<JobSummaryInfo> search(JobAdminSearchCommand command) {
        JobStatus status = command.status() == null ? null : command.status().toJobStatus();
        return jobRepositoryPort.findSummaryPage(status, command.type(), command.page(), command.size()).map(this::toInfo);
    }

    /** @throws JobException {@code NOT_FOUND_JOB} */
    public JobSummaryInfo get(long jobId) {
        return jobRepositoryPort.findSummary(jobId).map(this::toInfo).orElseThrow(() -> new JobException(JobErrorCode.NOT_FOUND_JOB));
    }

    private JobSummaryInfo toInfo(JobSummaryQueryResult job) {
        Optional<JobHandler> handler = jobHandlerProcessor.find(job.type()).map(JobHandlerProcessor.RegisteredJobHandler::handler);
        boolean active = job.status().isActive();
        return new JobSummaryInfo(
            job.jobId(),
            job.type(),
            handler.map(JobHandler::displayName).orElse(job.type()),
            job.status(),
            job.progressPercent(),
            job.progressMessage(),
            noticeOf(job, handler),
            active && !job.cancelRequested(),
            job.requestedAt(),
            job.finishedAt()
        );
    }

    /** 운영자가 알아야 할 것 하나. 끝난 성공 · 취소에는 없다. */
    private static String noticeOf(JobSummaryQueryResult job, Optional<JobHandler> handler) {
        if (job.status() == JobStatus.FAILED) {
            return handler.map(registered -> registered.failureNotice(job.errorCode()))
                .or(() -> Optional.ofNullable(ENGINE_FAILURE_NOTICES.get(job.errorCode())))
                .orElse(NOTICE_FAILED);
        }
        if (job.status().isActive() && job.cancelRequested()) {
            return NOTICE_CANCEL_REQUESTED;
        }
        if (job.status() == JobStatus.QUEUED && job.errorCode() != null) {
            return NOTICE_RETRY_WAITING;
        }
        return null;
    }
}
