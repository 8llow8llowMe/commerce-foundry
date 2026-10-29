package com.commercefoundry.domainlayer.skucost.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * SKU 원가 이력 오류 코드 ({@code coding-conventions.md} §8). 접두어는 서비스 단위 {@code PRODUCT_} 이고 원가는 074~ 를 쓴다
 * (001~ 상품 · 02x · 03x 재고 · 040~045 어드민 카테고리 — {@code ProductErrorCode}, 046~058 메인 전시 — {@code DisplayErrorCode},
 * 059~073 사이즈표 — {@code SizeChartErrorCode}). 새 코드를 더할 때 네 enum 의 번호가 겹치지 않게 한다.
 * 없는 SKU 는 상품 컨텍스트의 404 {@code PRODUCT_002} 를 그대로 쓴다. 필드별 검증 코드(200~)는 {@link SkuCostValidationMessage} 에 둔다.
 *
 * <p>메시지에 금액을 넣지 않는다 — 원가는 고객에게 보이면 안 되는 값이라 로그 · 오류 응답에 남기지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum SkuCostErrorCode {

    /** 적용 시작 시각이 지금 + 5분보다 뒤다. 예약 적용은 받지 않는다. */
    EFFECTIVE_FROM_IN_FUTURE("PRODUCT_074", "적용 시작 시각은 지금부터 5분 뒤까지만 지정할 수 있습니다.", HttpStatus.BAD_REQUEST),
    /** 적용 시작 시각이 같은 SKU · 유형의 최신 행보다 뒤가 아니다. 그 사이 다른 어드민이 더 늦은 행을 넣었을 수 있어 409 다. */
    EFFECTIVE_FROM_NOT_AFTER_LATEST("PRODUCT_075", "적용 시작 시각은 같은 유형의 최신 원가 적용 시작 시각(%s)보다 뒤여야 합니다. 이력을 다시 조회해 주세요.",
        HttpStatus.CONFLICT),
    /** 같은 SKU · 유형의 기존 행과 통화가 다르다. 타임라인 통화는 첫 행으로 고정된다. */
    CURRENCY_MISMATCH("PRODUCT_076", "이 원가 유형은 %s 통화로 기록되어 있습니다. 같은 통화로 입력해 주세요.", HttpStatus.CONFLICT),
    /** 같은 SKU · 유형 · 적용 시작 시각의 행이 먼저 커밋되어 유니크 키에 걸렸다. */
    COST_CONFLICT("PRODUCT_077", "같은 적용 시작 시각의 원가가 먼저 저장되었습니다. 이력을 다시 조회해 주세요.", HttpStatus.CONFLICT),
    /** SKU 행 잠금을 시간 상한 안에 얻지 못했다. */
    SKU_COST_BUSY("PRODUCT_078", "다른 원가 저장을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
