package com.commercefoundry.domainlayer.order.application.exception;

import java.util.Objects;
import lombok.Getter;

/** Port failure reasons; remote messages and codes never become customer-facing messages. */
@Getter
public class StockReservationException extends RuntimeException {

    private final Failure failure;

    public StockReservationException(Failure failure, Throwable cause) {
        super("stock reservation failed: " + failure, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public StockReservationException(Failure failure) {
        this(failure, null);
    }

    public enum Failure {
        INSUFFICIENT, NOT_PURCHASABLE, ITEMS_MISMATCH, CONFLICT,
        /** 확정 대상 선점이 이미 해제 · 만료됐다(확정의 409 {@code PRODUCT_025}, #308) — 같은 주문 아이디로 재선점할 신호다. 선점 호출에서는 나오지 않는다. */
        RELEASED,
        /** 확정 대상 선점이 없다(확정의 404 {@code PRODUCT_003}, #308) — 재선점할 신호다. 선점 호출에서는 나오지 않는다. */
        NOT_FOUND,
        /** 해제 대상 선점이 이미 확정됐다(해제의 409 {@code PRODUCT_024}, #308 PR-C1) — 결제 반영 복구창 프로브의 "확정 선점 증거" 다. */
        CONFIRMED,
        /** 복원 대상 선점이 확정(CONFIRMED)이 아니다(복원의 409 {@code PRODUCT_027}) — 다시 불러도 같다(버그 신호). */
        NOT_CONFIRMED,
        /** 복원 수량이 (선점 수량 − 이미 복원한 수량)을 넘는다(복원의 409 {@code PRODUCT_028}) — 다른 경로가 이미 복원했다. */
        QUANTITY_EXCEEDED,
        /** 같은 클레임에 내용이 다른 복원이 이미 있다(복원의 409 {@code PRODUCT_029}) — 다시 불러도 같다(버그 신호). */
        RESTORATION_MISMATCH,
        /** Transport, timeout, 5xx, circuit or invalid success response: reservation outcome is unknown. */
        UNKNOWN,
        /** Unexpected 4xx: request, authentication or contract rejection. */
        REJECTED
    }
}
