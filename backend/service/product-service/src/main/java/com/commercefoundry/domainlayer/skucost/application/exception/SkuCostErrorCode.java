package com.commercefoundry.domainlayer.skucost.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * SKU 원가 이력 오류 코드 ({@code coding-conventions.md} §8). product-service 의 {@code PRODUCT_} 검증 대역(1xx)이 다 차서
 * SKU 원가는 컨텍스트 접두어 {@code SKU_COST_} 를 새로 연다 (§8-2, 선례: 룩의 {@code LOOK_}). 업무 오류는 001~, 필드별 검증 코드(101~)는
 * {@link SkuCostValidationMessage} 에 둔다. 없는 SKU 는 상품 컨텍스트의 404 {@code PRODUCT_002} 를 그대로 쓴다.
 *
 * <p>요청 검증 실패는 이 enum 이 아니라 서비스 공용 검증 처리({@code ProductExceptionHandler})가 봉투로 바꾼다 — 메시지 앞의 {@code SKU_COST_1xx} 를 그대로 쓰고,
 * 역직렬화 실패 · 타입 불일치 · 필수 파라미터 누락은 서비스 공용 {@code PRODUCT_100} · {@code PRODUCT_198} · {@code PRODUCT_199} 다.
 *
 * <p>메시지에 금액을 넣지 않는다 — 원가는 고객에게 보이면 안 되는 값이라 로그 · 오류 응답에 남기지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum SkuCostErrorCode {

    /** 적용 시작 시각이 지금 + 5분보다 뒤다. 예약 적용은 받지 않는다. */
    EFFECTIVE_FROM_IN_FUTURE("SKU_COST_001", "적용 시작 시각은 지금부터 5분 뒤까지만 지정할 수 있습니다.", HttpStatus.BAD_REQUEST),
    /** 적용 시작 시각이 같은 SKU · 유형의 최신 행보다 뒤가 아니다. 그 사이 다른 어드민이 더 늦은 행을 넣었을 수 있어 409 다. */
    EFFECTIVE_FROM_NOT_AFTER_LATEST("SKU_COST_002", "적용 시작 시각은 같은 유형의 최신 원가 적용 시작 시각(%s)보다 뒤여야 합니다. 이력을 다시 조회해 주세요.",
        HttpStatus.CONFLICT),
    /** 같은 SKU · 유형의 기존 행과 통화가 다르다. 타임라인 통화는 첫 행으로 고정된다. */
    CURRENCY_MISMATCH("SKU_COST_003", "이 원가 유형은 %s 통화로 기록되어 있습니다. 같은 통화로 입력해 주세요.", HttpStatus.CONFLICT),
    /** 같은 SKU · 유형 · 적용 시작 시각의 행이 먼저 커밋되어 유니크 키에 걸렸다. */
    COST_CONFLICT("SKU_COST_004", "같은 적용 시작 시각의 원가가 먼저 저장되었습니다. 이력을 다시 조회해 주세요.", HttpStatus.CONFLICT),
    /** SKU 행 잠금을 시간 상한 안에 얻지 못했다. */
    SKU_COST_BUSY("SKU_COST_005", "다른 원가 저장을 처리 중입니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 이미 원가가 있는 유형인데 적용 시작 시각이 지금보다 앞이다. 지난 구간(그 구간의 주문)의 원가를 바꾸지 않는다. 첫 행만 과거 시작을 받는다. */
    RETROACTIVE_NOT_ALLOWED("SKU_COST_006", "이미 원가가 있는 유형은 지금 이후부터만 적용할 수 있습니다. 지난 구간의 원가는 바꾸지 않습니다.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
