package com.commercefoundry.domainlayer.storenavigation.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** {@code CONFLICT}, {@code ITEMS}~{@code VISIBLE}, {@code DESCRIPTION}, {@code PLACEMENT} 상수 이름은 {@code StoreNavigationRuleException.Reason} 과 같다(Processor 가 이름으로 바꾼다). */
@Getter
@RequiredArgsConstructor
public enum StoreNavigationErrorCode {
    NOT_FOUND("STORE_NAV_001", "조회할 상단 메뉴 설정 또는 게시본이 없습니다.", HttpStatus.NOT_FOUND),
    CONFLICT("STORE_NAV_020", "상단 메뉴 설정이 변경되었습니다. 다시 조회해 주세요.", HttpStatus.CONFLICT),
    INVALID_REQUEST("STORE_NAV_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    VERSION("STORE_NAV_101", "버전은 0 이상이어야 합니다.", HttpStatus.BAD_REQUEST),
    ITEMS("STORE_NAV_102", "메뉴 항목 목록은 필수이며 null 항목 없이 전체 최대 60개, 한 상위 항목 아래 최대 20개입니다.", HttpStatus.BAD_REQUEST),
    KEY("STORE_NAV_103", "항목 key 는 영문 소문자·숫자로 시작하는 소문자·숫자·하이픈 1~40자이며 목록 안에서 유일해야 합니다.", HttpStatus.BAD_REQUEST),
    PARENT("STORE_NAV_104", "상위 항목 key 는 목록에 있는 다른 항목이어야 합니다.", HttpStatus.BAD_REQUEST),
    CYCLE("STORE_NAV_105", "상위 항목 관계에 순환이 있습니다.", HttpStatus.BAD_REQUEST),
    DEPTH("STORE_NAV_106", "메뉴 깊이는 최대 3단계입니다.", HttpStatus.BAD_REQUEST),
    TYPE("STORE_NAV_107", "메뉴 유형이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    LABELS("STORE_NAV_108", "표시명은 두 언어 모두 제어문자 없는 1~40자여야 합니다.", HttpStatus.BAD_REQUEST),
    URL("STORE_NAV_109", "INTERNAL 은 / 로 시작하는 내부 경로, EXTERNAL 은 허용된 HTTPS 주소여야 하며 CATEGORY·NONE 은 URL 을 넣지 않습니다.",
        HttpStatus.BAD_REQUEST),
    CATEGORY("STORE_NAV_110", "CATEGORY 유형은 양수 카테고리 ID 가 필수이고 다른 유형은 카테고리 ID 를 넣지 않습니다.", HttpStatus.BAD_REQUEST),
    ORDER("STORE_NAV_111", "표시 순서는 같은 상위 항목 아래에서 중복 없는 0~999여야 합니다.", HttpStatus.BAD_REQUEST),
    VISIBLE("STORE_NAV_112", "노출 여부는 필수입니다.", HttpStatus.BAD_REQUEST),
    REVISION("STORE_NAV_113", "리비전 번호는 양수여야 합니다.", HttpStatus.BAD_REQUEST),
    DESCRIPTION("STORE_NAV_114", "설명은 없거나 두 언어 모두 제어문자 없는 1~80자여야 합니다.", HttpStatus.BAD_REQUEST),
    PLACEMENT("STORE_NAV_115", "PC 1차 메뉴·모바일 바로가기는 최상위 항목만 켤 수 있습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("STORE_NAV_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_REQUIRED("STORE_NAV_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
