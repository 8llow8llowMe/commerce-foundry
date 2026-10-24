package com.commercefoundry.domainlayer.job.application.command;

import com.commercefoundry.domainlayer.job.application.model.JobStatusFilter;

/**
 * 어드민 작업 목록 조건. 형식 검증(종류 패턴 · 페이지 범위)은 컨트롤러가 끝냈다.
 *
 * @param status 상태. 없으면 전체
 * @param type 작업 종류. 없으면 전체
 * @param page 0 부터 센다
 * @param size 1 ~ 100
 */
public record JobAdminSearchCommand(JobStatusFilter status, String type, int page, int size) {
}
