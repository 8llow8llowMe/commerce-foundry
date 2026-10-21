package com.commercefoundry.domainlayer.order.application.exception;

import java.util.Objects;
import lombok.Getter;

/**
 * {@code OrderRepositoryPort#insert} 의 실패 — 부른 쪽이 사유로 분기해야 하는 port 계약 예외다 ({@code coding-conventions.md} §8-1). 응답 코드는 Processor · Facade 가 붙인다.
 *
 * <p><b>원인 예외를 싣지 않는다.</b> DB 제약 위반의 메시지에는 위반한 행 값("Failing row contains ...")이 들어 있어 배송지 같은 개인정보가 로그 · 응답으로 샐 수 있다.
 * 원인의 예외 타입 이름만 {@link #getCauseType()} 으로 남긴다.
 */
@Getter
public class OrderSaveException extends RuntimeException {

    private final Reason reason;
    private final String causeType;

    public OrderSaveException(Reason reason, String causeType) {
        super("order save failed: " + reason);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.causeType = causeType;
    }

    public enum Reason {

        /** 같은 주문 아이디(PK) · 같은 재고 선점({@code uk_orders_stock_reservation_id})의 주문이 이미 저장됐다 — 같은 요청 기록을 이어받은 다른 시도가 먼저 저장했다. */
        ALREADY_SAVED,
        /** 그 밖의 DB 거절 — 버그 · 데이터 이상이다. */
        REJECTED
    }
}
