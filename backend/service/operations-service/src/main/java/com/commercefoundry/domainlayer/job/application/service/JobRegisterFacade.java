package com.commercefoundry.domainlayer.job.application.service;

import com.commercefoundry.domainlayer.job.application.command.JobRegisterCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobRegistrationInfo;
import com.commercefoundry.domainlayer.job.application.port.in.JobRegisterUseCase;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.job.domain.model.JobRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 작업 등록 진입. 트랜잭션을 이 Facade 에 걸지 않는다 — 같은 멱등 키가 동시에 등록돼 INSERT 가 유니크 키에서 지면 PostgreSQL 이 그 트랜잭션을 중단시켜,
 * 이긴 작업을 같은 트랜잭션에서 다시 읽을 수 없다. 등록({@link JobRegistrationProcessor#register})과 다시 읽기를 Processor 의 각자 트랜잭션으로 나눈다.
 */
@Service
@RequiredArgsConstructor
public class JobRegisterFacade implements JobRegisterUseCase {

    private final JobRegistrationProcessor jobRegistrationProcessor;

    @Override
    public JobRegistrationInfo register(JobRegisterCommand command) {
        try {
            return JobRegistrationInfo.of(jobRegistrationProcessor.register(command.toRequest()));
        } catch (JobException exception) {
            if (exception.getErrorCode() != JobErrorCode.JOB_DEDUP_CONFLICT) {
                throw exception;
            }
            // 이긴 쪽이 커밋한 뒤라 새 트랜잭션에서 보인다. 그 사이 이미 끝났으면(키가 비었으면) 충돌을 그대로 알린다 — 다시 시도하면 새로 등록된다
            return jobRegistrationProcessor.findActiveByDedupKey(command.type(), command.dedupKey())
                .map(winner -> JobRegistrationInfo.of(new JobRegistration(winner, false)))
                .orElseThrow(() -> exception);
        }
    }
}
