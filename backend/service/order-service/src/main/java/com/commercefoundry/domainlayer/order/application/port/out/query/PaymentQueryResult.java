package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * payment-service 결제 재조회 결과 (#308, {@code GET /internal/v1/payments/{paymentId}}). 원격 응답 모양(Feign DTO)은 adapter 밖으로 나오지 않는다.
 * 어댑터가 요청한 결제 아이디 · 아이디 형식 · 금액 · 통화 · 상태 코드 · 결제 완료의 승인 시각을 확인한 뒤 만든다. 주문 · 회원 · 금액 대조는 부른 쪽이 한다.
 *
 * @param statusCode payment {@code PaymentStatus} 제어 코드 (예: {@value #DONE}) — 모르는 새 값이 올 수 있어 문자열로 둔다
 * @param amount 결제 금액 (최소 단위 정수 + 통화)
 * @param approvedAt 승인 시각 (UTC). 승인 전이면 null — {@value #DONE} 이면 언제나 있다
 */
public record PaymentQueryResult(long paymentId, long orderId, long memberId, String statusCode, Money amount, Instant approvedAt) {

    /** 결제 완료(승인) 상태 코드. */
    public static final String DONE = "DONE";

    public PaymentQueryResult {
        Objects.requireNonNull(amount, "amount");
        if (paymentId <= 0 || orderId <= 0 || memberId <= 0) {
            throw new IllegalArgumentException("positive payment, order and member IDs required");
        }
        if (statusCode == null || statusCode.isBlank()) {
            throw new IllegalArgumentException("payment status code required");
        }
        if (DONE.equals(statusCode) && approvedAt == null) {
            throw new IllegalArgumentException("approved payment requires approvedAt");
        }
    }

    /** 결제가 승인(DONE) 상태인가 — 아니면 주문에 반영하지 않는다(이미 취소 등). */
    public boolean isDone() {
        return DONE.equals(statusCode);
    }
}
