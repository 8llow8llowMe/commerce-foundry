package com.commercefoundry.domainlayer.legaldraftgeneration.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LegalDraftErrorCode {
    NOT_FOUND("LEGAL_DRAFT_GENERATION_001", "생성 작업을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    KEY_CONFLICT("LEGAL_DRAFT_GENERATION_002", "같은 요청 키에 다른 입력이 등록되어 있습니다.", HttpStatus.CONFLICT),
    TEMPLATE_CONFLICT("LEGAL_DRAFT_GENERATION_003", "시작 템플릿을 다시 조회해 주세요.", HttpStatus.CONFLICT),
    REVIEW_UNAVAILABLE("LEGAL_DRAFT_GENERATION_004", "생성에 성공한 결과만 검토 확인할 수 있습니다.", HttpStatus.CONFLICT),
    CANCEL_CONFLICT("LEGAL_DRAFT_GENERATION_005", "작업 상태가 바뀌었습니다. 다시 조회해 주세요.", HttpStatus.CONFLICT),
    INVALID_REQUEST("LEGAL_DRAFT_GENERATION_100", "법률 문서 생성 요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    DISABLED("LEGAL_DRAFT_GENERATION_501", "법률 문서 AI 생성이 꺼져 있습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    RESULT_UNAVAILABLE("LEGAL_DRAFT_GENERATION_502", "검토용 생성 결과를 읽을 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus status;
}
