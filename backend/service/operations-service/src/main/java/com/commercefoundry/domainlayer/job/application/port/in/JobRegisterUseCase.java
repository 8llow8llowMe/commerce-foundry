package com.commercefoundry.domainlayer.job.application.port.in;

import com.commercefoundry.domainlayer.job.application.command.JobRegisterCommand;
import com.commercefoundry.domainlayer.job.application.info.JobRegistrationInfo;

/**
 * 작업 등록 (진입점용 — 이벤트 리스너 등). 같은 서비스의 기능 Facade 는 이 UseCase 대신 {@code JobRegistrationProcessor} 를 주입해
 * 자기 트랜잭션 안에서 부른다.
 */
public interface JobRegisterUseCase {

    /**
     * 작업을 QUEUED 로 등록하고 바로 돌려준다 — 실행은 워커가 한다. 같은 멱등 키의 활성 작업이 있으면(동시 등록에서 진 경우 포함) 그 작업을 돌려준다.
     *
     * @throws com.commercefoundry.domainlayer.job.application.exception.JobException {@code UNKNOWN_JOB_TYPE} · {@code JOB_PAYLOAD_TOO_LARGE}
     */
    JobRegistrationInfo register(JobRegisterCommand command);
}
