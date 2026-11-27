package com.commercefoundry.domainlayer.visitanalytics.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum VisitAnalyticsErrorCode {
    CLIENT_SUBJECT_REQUIRED("VISIT_ANALYTICS_001", "방문 분석 수집 경로를 사용할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    BODY_TOO_LARGE("VISIT_ANALYTICS_002", "이벤트 본문은 1KiB 이하여야 합니다.", HttpStatus.CONTENT_TOO_LARGE),
    UNSUPPORTED_BODY("VISIT_ANALYTICS_003", "이벤트 본문 형식을 지원하지 않습니다.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INVALID_EVENT("VISIT_ANALYTICS_100", "상품 조회 이벤트 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_PERIOD("VISIT_ANALYTICS_101", "조회 날짜는 보유 기간 안에서 최대 31일이어야 합니다.", HttpStatus.BAD_REQUEST),
    INVALID_PRODUCT("VISIT_ANALYTICS_102", "상품 식별자 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
