package com.commercefoundry.domainlayer.payment.application.exception;

import java.util.Objects;
import lombok.Getter;

/**
 * 결제 시도 저장이 다른 요청 · 다른 결제 시도와 부딪혔다 ({@code PaymentRepositoryPort#save}). 업무로 다룰 수 있는 충돌만 이 예외 하나로 올린다 —
 * application 이 spring-orm · Hibernate 예외 타입에 기대지 않게.
 *
 * <p>포트 계약의 예외다 — 결제 오류 코드(3종 세트)는 결제 API 와 함께 생기므로 그 Processor 가 {@link #getKind()} 를 보고 업무 오류로 바꾼다.
 * 원인 예외를 싣지 않는다 — DB 오류 메시지가 예외 메시지 · 스택으로 퍼지지 않게 충돌 종류와 아이디만 남긴다 ({@code coding-conventions.md} §8, #173 2차 방어).
 */
@Getter
public class PaymentPersistenceConflictException extends RuntimeException {

    private final Kind kind;
    private final long paymentId;
    private final long orderId;

    public PaymentPersistenceConflictException(Kind kind, long paymentId, long orderId) {
        super("결제 시도 저장이 충돌했습니다. kind=" + kind + ", paymentId=" + paymentId + ", orderId=" + orderId);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.paymentId = paymentId;
        this.orderId = orderId;
    }

    /** 충돌 종류. */
    public enum Kind {
        /** 같은 주문에 활성 결제 시도가 이미 있다 ({@code uk_payment_active_order_id}) — 동시 결제 준비, 또는 기존 시도를 버리지 않고 새 시도를 만들었다 */
        ACTIVE_SLOT,
        /** 결제키가 다른 결제 시도에 이미 쓰였다 ({@code uk_payment_payment_key}) — 다른 시도의 결제키로 승인을 시작하려 했다(변조 · 잘못된 짝) */
        PAYMENT_KEY,
        /** 읽은 뒤 다른 요청(동시 confirm · 재조회 · 웹훅)이 먼저 바꿨다 (낙관적 락 버전) */
        VERSION
    }
}
