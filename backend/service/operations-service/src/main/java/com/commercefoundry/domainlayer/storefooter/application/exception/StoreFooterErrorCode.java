package com.commercefoundry.domainlayer.storefooter.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreFooterErrorCode {
    NOT_FOUND("STORE_FOOTER_001", "조회할 푸터 설정 또는 게시본이 없습니다.", HttpStatus.NOT_FOUND),
    CONFLICT("STORE_FOOTER_020", "푸터 설정이 변경되었습니다. 다시 조회해 주세요.", HttpStatus.CONFLICT),
    INVALID_REQUEST("STORE_FOOTER_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    VERSION("STORE_FOOTER_101", "버전은 0 이상이어야 합니다.", HttpStatus.BAD_REQUEST),
    COPYRIGHT("STORE_FOOTER_102", "저작권 문구는 언어별 null 또는 제어문자 없는 1~200자여야 합니다.", HttpStatus.BAD_REQUEST),
    LINKS("STORE_FOOTER_103", "링크 목록은 필수이며 null 항목 없이 최대 20개입니다.", HttpStatus.BAD_REQUEST),
    TYPE("STORE_FOOTER_104", "링크 유형이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    LABELS("STORE_FOOTER_105", "표시명은 두 언어 모두 제어문자 없는 1~80자여야 합니다.", HttpStatus.BAD_REQUEST),
    URL("STORE_FOOTER_106", "허용된 HTTPS 링크를 입력해 주세요.", HttpStatus.BAD_REQUEST),
    ORDER("STORE_FOOTER_107", "표시 순서는 중복 없는 0~999여야 합니다.", HttpStatus.BAD_REQUEST),
    VISIBLE("STORE_FOOTER_108", "노출 여부는 필수입니다.", HttpStatus.BAD_REQUEST),
    REVISION("STORE_FOOTER_109", "리비전 번호는 양수여야 합니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("STORE_FOOTER_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("STORE_FOOTER_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
