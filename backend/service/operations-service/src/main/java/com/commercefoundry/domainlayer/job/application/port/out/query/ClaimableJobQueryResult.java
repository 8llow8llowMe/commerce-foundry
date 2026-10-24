package com.commercefoundry.domainlayer.job.application.port.out.query;

/** 가져갈 후보 한 건 — 아이디와 종류만 읽는다(payload 는 가져갈 때 다시 읽는다). */
public record ClaimableJobQueryResult(long jobId, String type) {
}
