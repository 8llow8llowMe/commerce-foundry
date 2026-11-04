package com.commercefoundry.domainlayer.legaldocument.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LegalDocumentErrorCode {
    NOT_FOUND("LEGAL_DOCUMENT_001", "조회할 문서가 없습니다.", HttpStatus.NOT_FOUND),
    CONFLICT("LEGAL_DOCUMENT_020", "문서가 변경되었습니다. 다시 조회해 주세요.", HttpStatus.CONFLICT),
    REVIEW_REQUIRED("LEGAL_DOCUMENT_021", "검토 확인 후 게시해 주세요.", HttpStatus.BAD_REQUEST),
    UNRESOLVED_PLACEHOLDER("LEGAL_DOCUMENT_022", "미치환 표시를 모두 수정한 뒤 게시해 주세요.", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST("LEGAL_DOCUMENT_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("LEGAL_DOCUMENT_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("LEGAL_DOCUMENT_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
