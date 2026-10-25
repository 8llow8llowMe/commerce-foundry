package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.application.info.ExpiredJobLeaseInfo;
import com.commercefoundry.domainlayer.job.application.model.JobLeaseCursor;
import com.commercefoundry.domainlayer.job.application.port.in.JobLeaseSweepUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobLeaseProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** lease 만료 판정. 트랜잭션은 Processor 의 건별 트랜잭션이다 — 한 건의 충돌이 배치를 되돌리지 않는다. */
@Service
@RequiredArgsConstructor
public class JobLeaseSweepFacade implements JobLeaseSweepUseCase {

    private final JobLeaseProcessor jobLeaseProcessor;

    @Override
    public List<ExpiredJobLeaseInfo> findExpiredLeases(JobLeaseCursor after, int limit) {
        return jobLeaseProcessor.findExpired(after, limit);
    }

    @Override
    public boolean expireLease(long jobId) {
        return jobLeaseProcessor.expire(jobId);
    }
}
