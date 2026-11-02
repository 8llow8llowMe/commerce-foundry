package com.commercefoundry.domainlayer.payment.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 결제 API 오류 코드 ({@code payment-guide.md} "고객 API 계약", {@code coding-conventions.md} §8-1). 메시지는 고객에게 그대로 보이므로 기술 용어를 쓰지 않는다.
 *
 * <p>검증 오류는 필드별 {@code PAYMENT_101~}({@link PaymentValidationMessage}), 폴백 {@code PAYMENT_100}, 대역 끝 {@code PAYMENT_198} · {@code PAYMENT_199} 다 (§8-2).
 */
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode {

    /** 결제 시도가 없거나 다른 회원의 것이다 — 남의 결제는 존재를 숨겨 404 다 ({@code api-design-guide.md} §2-2). */
    NOT_FOUND_PAYMENT("PAYMENT_001", "결제 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    /** order-service 가 주문이 없거나 이 회원의 주문이 아니라고 답했다(404 {@code ORDER_001}). */
    NOT_FOUND_ORDER("PAYMENT_002", "주문을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    /** 주문이 결제 대기(PENDING_PAYMENT)가 아니다 — 이미 결제됐거나 취소 · 만료됐다. */
    ORDER_NOT_PAYABLE("PAYMENT_003", "결제할 수 없는 주문입니다. 주문 상태를 확인해 주세요.", HttpStatus.CONFLICT),
    /** 주문의 결제 기한이 지났거나 결제창을 열기에는 너무 가깝다({@code payment.prepare.min-remaining}). */
    PAYMENT_DUE_TOO_CLOSE("PAYMENT_004", "결제 가능 시간이 얼마 남지 않았거나 지났습니다. 주문을 다시 진행해 주세요.", HttpStatus.CONFLICT),
    /** 지금은 KRW 주문만 결제한다 — USD(PayPal)는 해외 결제 이슈에서 연다. */
    CURRENCY_NOT_SUPPORTED("PAYMENT_005", "지금은 원화 결제만 할 수 있습니다.", HttpStatus.CONFLICT),
    /** 이미 끝난 결제 시도(FAILED · ABORTED · LATE_APPROVED)에 승인을 요청했다 — 결제 준비부터 다시 한다. */
    PAYMENT_ATTEMPT_CLOSED("PAYMENT_006", "이미 끝난 결제 요청입니다. 결제를 처음부터 다시 진행해 주세요.", HttpStatus.CONFLICT),
    /** 같은 결제 시도의 승인이 처리 중이다(IN_PROGRESS) — 잠시 뒤 상태 조회로 확인한다. */
    APPROVAL_IN_PROGRESS("PAYMENT_007", "결제를 승인하고 있습니다. 잠시 후 결제 상태를 확인해 주세요.", HttpStatus.CONFLICT),
    /** 승인 요청의 금액 · 통화가 결제 시도와 다르다 — 결제사에 승인을 요청하지 않는다 ({@code payment-guide.md} §3-1). */
    AMOUNT_MISMATCH("PAYMENT_008", "결제 금액이 주문 금액과 다릅니다. 결제를 처음부터 다시 진행해 주세요.", HttpStatus.BAD_REQUEST),
    /** 승인 요청의 결제키가 이 결제 시도에 기록된 결제키와 다르거나 다른 결제 시도의 것이다. */
    PAYMENT_KEY_MISMATCH("PAYMENT_009", "결제 정보가 일치하지 않습니다. 결제를 처음부터 다시 진행해 주세요.", HttpStatus.BAD_REQUEST),
    /** 결제사에 승인 요청을 보내지 못했다(서킷 오픈 · 연결 실패 · 키 설정 오류) — 청구는 없다. 같은 요청으로 다시 시도한다({@code Retry-After}). */
    GATEWAY_UNAVAILABLE("PAYMENT_010", "결제사에 연결하지 못했습니다. 결제는 진행되지 않았으니 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** order-service 의 주문 요약을 받지 못했다(서킷 오픈 · 전송 실패 · 응답 계약 위반 · 서비스 인증 실패). */
    ORDER_SERVICE_UNAVAILABLE("PAYMENT_011", "주문 정보를 잠시 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    /** 같은 주문 · 같은 결제 시도에 요청이 겹쳤다(저장 충돌 뒤 다시 읽어도 답을 정할 수 없다). */
    PAYMENT_REQUEST_CONFLICT("PAYMENT_012", "결제 요청이 겹쳤습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.CONFLICT),
    /** 결제사 응답을 받은 뒤 결과를 기록하지 못했다 — 승인됐을 수 있다. IN_PROGRESS 기록이 복구 단서다 ({@code payment-guide.md} §3-3). */
    APPROVAL_RECORD_FAILED("PAYMENT_013", "결제 결과를 기록하지 못했습니다. 잠시 후 결제 상태를 확인해 주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    /** 같은 {@code Idempotency-Key} 를 다른 결제 시도(또는 다른 결제키)의 승인에 다시 썼다 ({@code api-design-guide.md} §6). */
    IDEMPOTENCY_KEY_REUSED("PAYMENT_014", "이미 다른 결제에 사용한 요청입니다. 결제를 처음부터 다시 진행해 주세요.", HttpStatus.CONFLICT),
    /** 운영 결제 게이트({@code payment.enabled})가 닫혀 있다 — 결제 준비 · 승인을 받지 않는다 ({@code payment-guide.md} §3-4 · §6). */
    PAYMENTS_DISABLED("PAYMENT_015", "지금은 결제를 받을 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    CANCELLATION_CONFLICT("PAYMENT_016", "취소 요청이 겹치거나 취소할 수 없는 결제입니다.", HttpStatus.CONFLICT),
    CANCELLATION_AMOUNT_INVALID("PAYMENT_017", "취소 금액이 남은 결제 금액을 초과합니다.", HttpStatus.CONFLICT),
    CANCELLATION_UNCONFIRMED("PAYMENT_018", "취소 결과를 확인 중입니다. 같은 요청으로 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    CANCELLATION_KEY_REUSED("PAYMENT_019", "이미 다른 취소 요청에 사용한 클레임입니다.", HttpStatus.CONFLICT),

    /** 접두어 없는 검증 메시지 · 역직렬화 실패(깨진 JSON · 타입 불일치)의 폴백. */
    INVALID_REQUEST("PAYMENT_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 경로 변수 · 헤더 형식 오류(바인딩 단계). */
    PARAMETER_TYPE_INVALID("PAYMENT_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    /** 필수 요청 파라미터 · 헤더 누락. */
    PARAMETER_REQUIRED("PAYMENT_199", "필수 요청 파라미터가 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
