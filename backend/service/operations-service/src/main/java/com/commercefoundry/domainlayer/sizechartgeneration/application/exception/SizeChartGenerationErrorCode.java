package com.commercefoundry.domainlayer.sizechartgeneration.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SizeChartGenerationErrorCode {
    NOT_FOUND("SIZE_CHART_GENERATION_001", "등록된 사이즈표 생성 작업이 없습니다.", HttpStatus.NOT_FOUND),
    INPUT_CONFLICT("SIZE_CHART_GENERATION_002", "초안의 입력 또는 등록 정보가 기존 영수증과 다릅니다.", HttpStatus.CONFLICT),
    EXPIRED("SIZE_CHART_GENERATION_005", "만료된 초안은 새로 등록할 수 없습니다.", HttpStatus.CONFLICT),
    RESULT_EXPIRED("SIZE_CHART_GENERATION_010", "작업 결과 보관 기간이 만료되었습니다.", HttpStatus.GONE),
    INVALID_REQUEST("SIZE_CHART_GENERATION_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    UNAVAILABLE("SIZE_CHART_GENERATION_501", "사이즈표 생성 작업을 등록할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    RESULT_UNAVAILABLE("SIZE_CHART_GENERATION_502", "사이즈표 생성 결과를 조회할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_RESULT("SIZE_CHART_GENERATION_503", "사이즈표 생성 결과 계약이 올바르지 않습니다.", HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
