package com.commercefoundry.domainlayer.job.application.info;

import com.commercefoundry.domainlayer.job.domain.model.JobRegistration;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;

/**
 * 등록 결과. 응답(202 + jobId)은 부른 진입의 Presenter 가 만든다 — 식별자는 거기서 String 이 된다.
 *
 * @param created 새로 만들었으면 true. 같은 멱등 키의 활성 작업을 돌려줬으면 false
 */
public record JobRegistrationInfo(long jobId, String type, JobStatus status, boolean created) {

    public static JobRegistrationInfo of(JobRegistration registration) {
        return new JobRegistrationInfo(registration.job().id(), registration.job().type(), registration.job().status(), registration.created());
    }
}
