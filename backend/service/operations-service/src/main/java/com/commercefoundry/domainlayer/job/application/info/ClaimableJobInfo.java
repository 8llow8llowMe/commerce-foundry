package com.commercefoundry.domainlayer.job.application.info;

/** 가져갈 후보. 워커는 종류별 동시 상한을 보고 고른 뒤 건마다 다시 읽어 가져간다. */
public record ClaimableJobInfo(long jobId, String type) {
}
