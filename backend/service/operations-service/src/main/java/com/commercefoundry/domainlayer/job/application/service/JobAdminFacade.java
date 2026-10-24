package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.adapter.in.admin.presenter.JobAdminPresenter;
import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.in.JobAdminUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobAdminAuditProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobQueryProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 작업 API. 목록 · 상세는 읽기 전용 트랜잭션이다.
 *
 * <p>취소에는 트랜잭션을 이 Facade 에 걸지 않는다 — {@link JobRunProcessor#requestCancel} 이 시도마다 자기 트랜잭션을 연다. 읽은 뒤 워커가 같은 작업을
 * 가져가거나 끝내면 버전 충돌({@code JOB_CONFLICT})이 나는데, 새 트랜잭션에서 다시 읽으면 바뀐 상태로 판정된다(가져갔으면 취소 요청, 끝났으면
 * {@code JOB_ALREADY_FINISHED}). 감사 로그는 결과가 정해진 뒤 트랜잭션 밖에서 한 번 남긴다.
 */
@Service
@RequiredArgsConstructor
public class JobAdminFacade implements JobAdminUseCase {

    /** 취소가 워커의 가져가기 · 종료 · heartbeat 와 겹쳐 버전에서 졌을 때 다시 읽어 시도하는 횟수(첫 시도 포함). */
    private static final int CANCEL_ATTEMPTS = 3;

    private final JobQueryProcessor jobQueryProcessor;
    private final JobRunProcessor jobRunProcessor;
    private final JobAdminAuditProcessor jobAdminAuditProcessor;
    private final JobAdminPresenter jobAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<JobAdminItem> getJobs(JobAdminSearchCommand command) {
        return jobAdminPresenter.toPageResponse(jobQueryProcessor.search(command));
    }

    @Override
    @Transactional(readOnly = true)
    public JobAdminResponse getJob(long jobId) {
        return jobAdminPresenter.toResponse(jobQueryProcessor.get(jobId));
    }

    /**
     * 트랜잭션을 걸지 않는다({@code architecture-guide.md} §3 Facade 예외 — 낙관적 충돌 재시도). 같은 행을 워커와 다퉈 버전에서 지면
     * 새 트랜잭션에서 다시 읽어야 바뀐 상태로 판정되므로, 트랜잭션은 시도마다 {@link JobRunProcessor#requestCancel} 이 연다.
     */
    @Override
    public JobAdminResponse cancel(long jobId, long adminMemberId) {
        Job cancelled;
        try {
            cancelled = requestCancel(jobId, adminMemberId);
        } catch (RuntimeException exception) {
            jobAdminAuditProcessor.cancelFailed(adminMemberId, jobId);
            throw exception;
        }
        jobAdminAuditProcessor.cancelSucceeded(adminMemberId, jobId);
        return jobAdminPresenter.toResponse(cancelled);
    }

    private Job requestCancel(long jobId, long adminMemberId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return jobRunProcessor.requestCancel(jobId, adminMemberId);
            } catch (JobException exception) {
                if (exception.getErrorCode() != JobErrorCode.JOB_CONFLICT || attempt >= CANCEL_ATTEMPTS) {
                    throw exception;
                }
            }
        }
    }
}
