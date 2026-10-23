package com.commercefoundry.domainlayer.payment.domain.model;

import java.util.Objects;
import lombok.Getter;

/**
 * PG 승인 결과(또는 다시 승인하려는 결제키)가 이 결제 시도와 어긋난다 — 금액이 다르거나, 다른 결제 시도의 결제키다. 승인으로 기록하지 않는다.
 * 길이 초과 같은 입력 오류({@link IllegalArgumentException})와 따로 둔다 — 돈이 빠졌을 수 있는 경로라 Processor 가 UNKNOWN
 * ({@link PaymentUnknownReason#APPROVAL_MISMATCH}) · 사람 확인({@link Payment#flagForManualReview})으로 돌린다.
 *
 * <p>어긋난 값(금액 · 결제키)은 싣지 않는다 — 결제키 전체가 예외 메시지 · 로그로 퍼지지 않게 ({@code coding-conventions.md} §8). 결제 시도 아이디와 항목만 싣는다.
 */
@Getter
public class PaymentApprovalMismatchException extends RuntimeException {

    private final long paymentId;
    private final Field field;

    public PaymentApprovalMismatchException(long paymentId, Field field) {
        super("승인 결과가 결제 시도와 다릅니다. paymentId=" + paymentId + ", field=" + field);
        this.paymentId = paymentId;
        this.field = Objects.requireNonNull(field, "field");
    }

    /** 어긋난 항목. */
    public enum Field {
        AMOUNT,
        PAYMENT_KEY
    }
}
