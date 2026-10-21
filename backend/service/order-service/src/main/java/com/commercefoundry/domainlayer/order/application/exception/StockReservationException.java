package com.commercefoundry.domainlayer.order.application.exception;

import java.util.Objects;
import lombok.Getter;

/**
 * {@code StockReservationPort} 의 실패 — 원격 응답 모양(상태 코드 · {@code PRODUCT_*})을 application 이 다룰 사유로만 알린다.
 *
 * <p>응답으로 나가지 않는 port 계약 예외다. 사용자 오류 코드({@code ORDER_016} · {@code ORDER_004} · {@code ORDER_018} · {@code ORDER_017} · {@code ORDER_002})는
 * 이 예외를 받은 {@code OrderStockProcessor} 가 정한다 — 같은 409 라도 고객이 할 일(수량 조정 · 같은 키로 재시도 · 문의)이 사유마다 다르기 때문이다.
 * order 는 어느 사유도 다시 보내지 않는다 — 같은 키의 재시도가 같은 주문 아이디로 다시 선점한다.
 */
@Getter
public class StockReservationException extends RuntimeException {

    private final Failure failure;

    /**
     * 원격 오류 메시지는 싣지 않는다 — {@code PRODUCT_021} · {@code PRODUCT_022} 메시지에는 SKU 아이디 목록이 있고, 고객 응답에 그대로 넘기지 않는다
     * ({@code order-flow-guide.md} §3-4). 고객 문구는 {@code OrderErrorCode} 의 것이다.
     */
    public StockReservationException(Failure failure, Throwable cause) {
        super("stock reservation failed: " + failure, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public StockReservationException(Failure failure) {
        this(failure, null);
    }

    public enum Failure {

        /** 재고 부족 (PRODUCT_021) — product-service 가 선점 전체를 되돌렸다. */
        INSUFFICIENT,
        /** 주문할 수 없는 SKU (PRODUCT_022) — 그 사이 판매 종료 · 숨김. */
        NOT_PURCHASABLE,
        /** 같은 주문의 활성 선점이 이미 있는데 항목이 다르다 (PRODUCT_023). 다시 보내도 같다. */
        ITEMS_MISMATCH,
        /** 이미 해제 · 만료된 선점의 확정 (PRODUCT_025) — 확정은 #14 가 쓴다. */
        ALREADY_RELEASED,
        /** 같은 선점을 동시에 바꿨다 (PRODUCT_026). 고객의 같은 키 재시도가 바뀐 상태로 답을 받는다. */
        CONFLICT,
        /** 없는 선점 (PRODUCT_003). */
        NOT_FOUND,
        /** 이미 확정된 선점의 해제 (PRODUCT_024). */
        ALREADY_CONFIRMED,
        /**
         * 결과를 모른다 — 서킷 오픈 · 전송 실패 · 타임아웃 · 5xx · 응답이 계약과 다름. 선점이 됐을 수도 있다.
         * 고객이 같은 키로 다시 보내면 같은 주문 아이디라 product-service 가 업무 키로 멱등하게 답한다.
         */
        UNKNOWN,
        /** 예상하지 못한 거절(그 밖의 4xx — 서비스 토큰 · 요청 형식). 우리 쪽 설정 · 계약 문제라 다시 보내도 같다. */
        REJECTED
    }
}
