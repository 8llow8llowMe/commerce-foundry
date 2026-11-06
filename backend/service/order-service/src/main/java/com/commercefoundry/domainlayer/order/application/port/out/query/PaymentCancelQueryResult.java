package com.commercefoundry.domainlayer.order.application.port.out.query;

import java.util.Objects;

/**
 * payment-service 결제 취소 호출 결과 (#16 PR-2) — 원격 응답 · 예외를 이 값으로만 바꿔 내보낸다(원격 본문 · cause 를 싣지 않는다).
 *
 * <ul>
 *   <li>{@link Outcome#SUCCEEDED} — 환불 확정(같은 클레임의 기존 성공 취소 포함)</li>
 *   <li>{@link Outcome#REJECTED} — 종결 거절(허용 목록만: {@code PAYMENT_016 · 017 · 019 · 001}, 400 의 {@code PAYMENT_1xx}). 같은 요청을 다시 보내도 같다. {@code failureCode} 는 그 코드</li>
 *   <li>{@link Outcome#UNKNOWN} — 그 밖의 전부(타임아웃 · 5xx · {@code PAYMENT_018} · {@code PAYMENT_012} · 모르는 4xx · 서킷 · 인증 설정 · 계약 위반). 같은 클레임 아이디 · 같은 본문으로 다시 보낸다</li>
 * </ul>
 *
 * @param failureCode REJECTED 면 40자 이하 코드, 그 밖에는 null
 */
public record PaymentCancelQueryResult(Outcome outcome, String failureCode) {

    public PaymentCancelQueryResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.REJECTED) != (failureCode != null) || (failureCode != null && (failureCode.isBlank() || failureCode.length() > 40))) {
            throw new IllegalArgumentException("failureCode is required only for REJECTED and must be 1~40 chars");
        }
    }

    public static PaymentCancelQueryResult succeeded() {
        return new PaymentCancelQueryResult(Outcome.SUCCEEDED, null);
    }

    public static PaymentCancelQueryResult rejected(String failureCode) {
        return new PaymentCancelQueryResult(Outcome.REJECTED, failureCode);
    }

    public static PaymentCancelQueryResult unknown() {
        return new PaymentCancelQueryResult(Outcome.UNKNOWN, null);
    }

    public enum Outcome {
        SUCCEEDED, REJECTED, UNKNOWN
    }
}
