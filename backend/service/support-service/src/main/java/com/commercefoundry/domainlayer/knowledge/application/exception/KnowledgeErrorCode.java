package com.commercefoundry.domainlayer.knowledge.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum KnowledgeErrorCode {

    NOT_FOUND("SUPPORT_001", "지식 문서 또는 리비전이 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_INPUT("SUPPORT_002", "지식 입력 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_TRANSITION("SUPPORT_003", "현재 상태에서는 이 작업을 수행할 수 없습니다.", HttpStatus.CONFLICT),
    CONCURRENTLY_CHANGED("SUPPORT_004", "지식 문서가 다른 요청에 의해 변경되었습니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
