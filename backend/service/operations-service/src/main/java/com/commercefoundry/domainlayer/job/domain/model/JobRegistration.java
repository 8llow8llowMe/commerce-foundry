package com.commercefoundry.domainlayer.job.domain.model;

import java.util.Objects;

/**
 * 작업 등록 결과. 다른 컨텍스트의 기능 Facade 가 받으므로 domain 에 둔다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙 2).
 *
 * @param created 새로 만들었으면 true. 같은 멱등 키의 활성 작업을 돌려줬으면 false
 */
public record JobRegistration(Job job, boolean created) {

    public JobRegistration {
        Objects.requireNonNull(job, "job");
    }
}
