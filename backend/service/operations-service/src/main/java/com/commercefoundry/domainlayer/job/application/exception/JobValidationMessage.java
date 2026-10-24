package com.commercefoundry.domainlayer.job.application.exception;

/**
 * 어드민 작업 API 요청 검증 메시지 — {@code "<코드>:<문구>"} ({@code coding-conventions.md} §8). 대역은 {@code JOB_1xx} 다.
 */
public final class JobValidationMessage {

    public static final String PAGE_MIN = "JOB_101:page 는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "JOB_102:size 는 1 이상 100 이하여야 합니다.";
    public static final String TYPE_PATTERN = "JOB_103:type 은 영문 대문자 · 숫자 · 밑줄 1 ~ 64자여야 합니다.";
    public static final String JOB_ID_POSITIVE = "JOB_104:jobId 는 양수여야 합니다.";

    private JobValidationMessage() {
    }
}
