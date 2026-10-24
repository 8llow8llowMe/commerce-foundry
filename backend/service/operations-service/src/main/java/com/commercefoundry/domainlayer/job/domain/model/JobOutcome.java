package com.commercefoundry.domainlayer.job.domain.model;

/**
 * 핸들러 실행 결과. 결과 파일(오브젝트 스토리지)은 PR-E 에서 더한다.
 *
 * @param resultJson 작은 결과 JSON ({@code operations.jobs.result.max-json-bytes} 이하, 개인정보 없이). 없으면 null
 */
public record JobOutcome(String resultJson) {

    public static JobOutcome empty() {
        return new JobOutcome(null);
    }
}
