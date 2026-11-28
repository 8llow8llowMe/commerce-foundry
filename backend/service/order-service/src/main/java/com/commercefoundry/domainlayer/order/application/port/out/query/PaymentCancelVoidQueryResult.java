package com.commercefoundry.domainlayer.order.application.port.out.query;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * payment-service 무환불 판정(void) 호출 결과 (#17 A4, {@code payment-guide.md} §8-1) — 원격 응답 · 예외를 이 값으로만 바꿔 내보낸다(원격 본문 · cause 를 싣지 않는다).
 * <b>취소 거절을 허락하는 값은 {@link Outcome#NO_REFUND_CONFIRMED} 하나다</b> — 나머지는 모두 거절을 막는 닫힌 쪽이다.
 *
 * <ul>
 *   <li>{@link Outcome#NO_REFUND_CONFIRMED} — 이 클레임으로 환불이 없었고 앞으로도 없다(payment 가 봉인했다). 성공 봉투 · 같은 결제 · 같은 클레임 · {@code sealed=true} 일 때만</li>
 *   <li>{@link Outcome#REFUNDED} — 이 클레임으로 환불이 확정됐다(우리 PG 취소 또는 외부 확인 환불). 거절하면 안 된다</li>
 *   <li>{@link Outcome#UNCONFIRMED} — payment 가 지금은 확정하지 못한다고 답했거나(PG 장애 밖의 사유) {@code PAYMENT_*} 4xx 로 답했다</li>
 *   <li>{@link Outcome#UNAVAILABLE} — 판정을 받지 못했다: PG 조회 장애({@code PG_UNAVAILABLE}) · 전송 실패 · 타임아웃 · 5xx · 서킷 열림 · 코드 없는 4xx · 계약 위반</li>
 * </ul>
 *
 * @param reasonCode 로그용 사유 코드 — payment 판정 이유({@code CLAIM_NEVER_REQUESTED} 등) · 오류 봉투의 resultCode · 어댑터의 분류 코드({@code CIRCUIT_OPEN} 등).
 *     영문 대문자로 시작하는 대문자 · 숫자 · 밑줄 40자 이하만 받는다 — 원격 임의 문자열을 그대로 싣지 않는다
 */
public record PaymentCancelVoidQueryResult(Outcome outcome, String reasonCode) {

    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    public PaymentCancelVoidQueryResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode must be 1~40 upper-case letters, digits or underscores");
        }
    }

    /** 사유 코드 모양인가 — 원격 판정 이유를 이 값에 실을지 어댑터가 먼저 본다. */
    public static boolean isReasonCode(String value) {
        return value != null && REASON_CODE.matcher(value).matches();
    }

    /** 취소 거절을 허락하는가 — {@link Outcome#NO_REFUND_CONFIRMED} 일 때만 true 다. */
    public boolean isNoRefundConfirmed() {
        return outcome == Outcome.NO_REFUND_CONFIRMED;
    }

    public enum Outcome {
        /** 무환불 확정 · 봉인 — 취소를 거절해도 된다. */
        NO_REFUND_CONFIRMED,
        /** 이 클레임으로 환불이 확정됐다 — 거절하지 않는다. */
        REFUNDED,
        /** payment 가 지금은 확정하지 못했다 — 거절하지 않는다. */
        UNCONFIRMED,
        /** 판정을 받지 못했다 — 거절하지 않고, 나중에 다시 부를 수 있다. */
        UNAVAILABLE
    }
}
