package com.commercefoundry.domainlayer.order.application.exception;

import java.util.Objects;
import lombok.Getter;

/**
 * {@code OrderPaymentRepositoryPort#insert} 의 실패 — 부른 쪽(결제 승인 컨슈머, #308 PR-B2)이 사유로 분기해야 하는 port 계약 예외다 ({@code coding-conventions.md} §8-1).
 * 이 예외가 나면 그 트랜잭션은 이미 rollback-only 다 — 같은 트랜잭션에서 잡고 계속하지 않고, 새 트랜잭션에서 다시 읽어 판정한다.
 *
 * <p>원인 예외를 싣지 않는다 — 제약 위반 메시지에 행 값이 들어 있다({@link OrderSaveException} 과 같다). 원인 타입 이름만 남긴다.
 */
@Getter
public class OrderPaymentSaveException extends RuntimeException {

    private final Reason reason;
    private final String causeType;

    public OrderPaymentSaveException(Reason reason, String causeType) {
        super("order payment save failed: " + reason);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.causeType = causeType;
    }

    public enum Reason {
        /** 같은 결제의 반영 기록이 이미 있다({@code uk_order_payment_payment_id}) — 다른 eventId 로 온 같은 결제다. 기존 기록을 읽어 같은 결과로 끝낸다. */
        PAYMENT_ALREADY_RECORDED,
        /** 이 주문에 다른 결제의 결제 완료 기록이 이미 있다({@code uk_order_payment_paid_order_id}) — {@code ALREADY_PAID} 거절 대상이다. */
        ORDER_ALREADY_PAID,
        /**
         * 같은 트랜잭션에 먼저 넣은 inbox 처리 기록이 이미 있다({@code uk_processed_event_consumer_group_event_id}) — 같은 이벤트의 정상 중복(동시 처리)이다.
         * 주문 반영은 먼저 커밋한 쪽이 했다. 오류가 아니다.
         */
        EVENT_ALREADY_PROCESSED,
        /** 그 밖의 DB 거절 — 버그 · 데이터 이상이다(check 위반 등). */
        REJECTED
    }
}
