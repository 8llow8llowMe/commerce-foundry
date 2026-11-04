package com.commercefoundry.domainlayer.payment.application.exception;

/**
 * 결제 요청 검증 메시지 ({@code coding-conventions.md} §8-2). {@code "PAYMENT_1xx:메시지"} — 앞이 필드 코드, 뒤가 고객에게 보이는 문구다.
 * 번호는 요청 DTO 의 필드 선언 순서다 (결제 준비 → 결제 승인 본문 → 금액 → 승인 헤더).
 */
public final class PaymentValidationMessage {

    // 결제 준비 (POST /api/v1/payments)
    public static final String ORDER_ID_REQUIRED = "PAYMENT_101:주문 아이디는 필수입니다.";
    public static final String ORDER_ID_POSITIVE = "PAYMENT_102:주문 아이디가 올바르지 않습니다.";

    // 결제 승인 (POST /api/v1/payments/confirm)
    public static final String PAYMENT_KEY_REQUIRED = "PAYMENT_103:결제 정보가 없습니다. 결제를 처음부터 다시 진행해 주세요.";
    public static final String PAYMENT_KEY_LENGTH_INVALID = "PAYMENT_104:결제 정보가 올바르지 않습니다. 결제를 처음부터 다시 진행해 주세요.";
    public static final String PG_ORDER_ID_REQUIRED = "PAYMENT_105:결제 요청 번호가 없습니다. 결제를 처음부터 다시 진행해 주세요.";
    public static final String PG_ORDER_ID_LENGTH_INVALID = "PAYMENT_106:결제 요청 번호가 올바르지 않습니다. 결제를 처음부터 다시 진행해 주세요.";
    public static final String PG_ORDER_ID_FORMAT_INVALID = "PAYMENT_107:결제 요청 번호가 올바르지 않습니다. 결제를 처음부터 다시 진행해 주세요.";
    public static final String AMOUNT_REQUIRED = "PAYMENT_108:결제 금액은 필수입니다.";

    // 금액 (amount.amount · amount.currency)
    public static final String AMOUNT_VALUE_REQUIRED = "PAYMENT_109:결제 금액은 필수입니다.";
    public static final String AMOUNT_VALUE_POSITIVE = "PAYMENT_110:결제 금액은 0보다 커야 합니다.";
    public static final String CURRENCY_REQUIRED = "PAYMENT_111:결제 통화는 필수입니다.";
    public static final String CURRENCY_FORMAT_INVALID = "PAYMENT_112:결제 통화가 올바르지 않습니다.";

    // 승인 헤더 Idempotency-Key — 컨트롤러가 UUID 로 바인딩하고, 누락 · 형식 오류는 PaymentExceptionHandler 가 이 코드로 답한다
    public static final String IDEMPOTENCY_KEY_REQUIRED = "PAYMENT_113:요청 정보가 부족합니다. 화면을 새로 고친 뒤 다시 시도해 주세요.";
    public static final String IDEMPOTENCY_KEY_FORMAT_INVALID = "PAYMENT_114:요청 정보가 올바르지 않습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.";

    // 서비스 간 결제 조회 경로 (GET /internal/v1/payments/{paymentId}) — 숫자가 아니면 PAYMENT_198
    public static final String PAYMENT_ID_POSITIVE = "PAYMENT_115:결제 아이디가 올바르지 않습니다.";

    // 어드민 결제 목록 페이지
    public static final String PAGE_MIN = "PAYMENT_116:페이지는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "PAYMENT_117:페이지 크기는 1~100이어야 합니다.";

    // 서비스 간 승인 집계
    public static final String SUMMARY_FROM_REQUIRED = "PAYMENT_118:집계 시작 시각은 필수입니다.";
    public static final String SUMMARY_FROM_FORMAT = "PAYMENT_119:집계 시작 시각 형식이 올바르지 않습니다.";
    public static final String SUMMARY_TO_REQUIRED = "PAYMENT_120:집계 종료 시각은 필수입니다.";
    public static final String SUMMARY_TO_FORMAT = "PAYMENT_121:집계 종료 시각 형식이 올바르지 않습니다.";

    private PaymentValidationMessage() {
    }
}
