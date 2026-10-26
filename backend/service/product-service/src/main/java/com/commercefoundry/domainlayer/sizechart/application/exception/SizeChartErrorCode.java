package com.commercefoundry.domainlayer.sizechart.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 사이즈표 오류 코드 ({@code coding-conventions.md} §8). 접두어는 서비스 단위 {@code PRODUCT_} 이고 사이즈표는 059~ 를 쓴다
 * (001~ 상품 · 02x · 03x 재고 · 040~045 어드민 카테고리 — {@code ProductErrorCode}, 046~058 메인 전시 — {@code DisplayErrorCode}).
 * 새 코드를 더할 때 세 enum 의 번호가 겹치지 않게 한다. 필드별 검증 코드(170~)는 {@link SizeChartValidationMessage} 에 둔다.
 *
 * <p>요청 검증 실패(PRODUCT_100 · 101 · 102 · 170~197 · 198 · 199)는 이 enum 이 아니라 서비스 공용 검증 처리({@code ProductExceptionHandler})가
 * 봉투로 바꾼다. 목록의 page · size 는 서비스 공용 101 · 102 다.
 */
@Getter
@RequiredArgsConstructor
public enum SizeChartErrorCode {

    /** 경로의 템플릿 아이디가 없다. */
    NOT_FOUND_TEMPLATE("PRODUCT_059", "존재하지 않는 사이즈표 템플릿입니다.", HttpStatus.NOT_FOUND),
    /** 한 템플릿 안에서 항목 이름이 두 번 있다. */
    COLUMN_NAME_DUPLICATED("PRODUCT_060", "템플릿 항목 이름이 중복입니다.", HttpStatus.BAD_REQUEST),
    /** 한 템플릿 안에서 항목 순서가 두 번 있다. */
    COLUMN_ORDER_DUPLICATED("PRODUCT_061", "템플릿 항목 순서가 중복입니다.", HttpStatus.BAD_REQUEST),
    /** 상품 사이즈표가 템플릿을 쓰는 동안 단위를 바꾸거나 항목을 넣거나 빼려 했다. */
    TEMPLATE_STRUCTURE_LOCKED("PRODUCT_062", "상품 사이즈표가 이 템플릿을 쓰고 있어 단위를 바꾸거나 항목을 추가 · 삭제할 수 없습니다.", HttpStatus.CONFLICT),
    /** 상품 사이즈표가 템플릿을 쓰는 동안 템플릿을 지우려 했다. */
    TEMPLATE_IN_USE("PRODUCT_063", "상품 사이즈표가 이 템플릿을 쓰고 있어 삭제할 수 없습니다.", HttpStatus.CONFLICT),
    /** 어드민 경로의 상품이 없다. 숨긴 상품은 있는 상품이다. 고객 조회는 상품 상세와 같은 PRODUCT_001 을 그대로 쓴다. */
    PRODUCT_NOT_FOUND("PRODUCT_064", "존재하지 않는 상품입니다.", HttpStatus.NOT_FOUND),
    /** 본문의 템플릿 아이디가 없다. 경로 리소스가 아니라 본문 참조라 400 이다. */
    TEMPLATE_REFERENCE_NOT_FOUND("PRODUCT_065", "존재하지 않는 사이즈표 템플릿입니다.", HttpStatus.BAD_REQUEST),
    /** 사이즈 행이 템플릿에 없는 항목을 가리킨다. */
    CELL_COLUMN_UNKNOWN("PRODUCT_066", "템플릿에 없는 항목입니다.", HttpStatus.BAD_REQUEST),
    /** 사이즈 행에 템플릿 항목이 빠졌다. */
    CELL_COLUMN_MISSING("PRODUCT_067", "사이즈 행에 템플릿 항목이 빠졌습니다.", HttpStatus.BAD_REQUEST),
    /** 한 사이즈 행에 같은 항목이 두 번 있다. */
    CELL_COLUMN_DUPLICATED("PRODUCT_068", "한 사이즈 행에 같은 항목이 두 번 있습니다.", HttpStatus.BAD_REQUEST),
    /** 한 사이즈표에 같은 사이즈 이름이 두 번 있다. */
    ROW_LABEL_DUPLICATED("PRODUCT_069", "사이즈 이름이 중복입니다.", HttpStatus.BAD_REQUEST),
    /** 한 사이즈표에 같은 사이즈 순서가 두 번 있다. */
    ROW_ORDER_DUPLICATED("PRODUCT_070", "사이즈 순서가 중복입니다.", HttpStatus.BAD_REQUEST),
    /** 쓰는 중인 템플릿을 고치는데 항목 아이디가 두 번 있다. */
    COLUMN_ID_DUPLICATED("PRODUCT_071", "템플릿 항목 아이디가 중복입니다.", HttpStatus.BAD_REQUEST),
    /** 템플릿 행 잠금을 시간 상한 안에 얻지 못했다. */
    SIZE_CHART_BUSY("PRODUCT_072", "다른 사이즈표 변경을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 같은 상품의 사이즈표 생성이 먼저 커밋되어 상품 유니크에 걸렸다. */
    CHART_CONFLICT("PRODUCT_073", "다른 사이즈표 저장이 먼저 반영되었습니다. 다시 조회해 주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
