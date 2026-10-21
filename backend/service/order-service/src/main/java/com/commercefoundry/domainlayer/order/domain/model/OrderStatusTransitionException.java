package com.commercefoundry.domainlayer.order.domain.model;

import java.util.Objects;
import lombok.Getter;

/**
 * 주문 상태의 허용 전이 표({@link OrderStatus}) 밖 요청.
 *
 * <p>domain 소유 예외다. domain 은 application 의 오류 코드를 모르므로 현재 상태와 시도한 상태만 싣고, 업무 오류 코드
 * ({@code ORDER_STATUS_TRANSITION_INVALID}, 409)는 이 예외를 받은 Processor 가 붙인다. 흘러 나오면 {@code OrderExceptionHandler} 가 같은 409 로 받는다.
 */
@Getter
public class OrderStatusTransitionException extends RuntimeException {

    private final OrderStatus currentStatus;
    private final OrderStatus targetStatus;

    public OrderStatusTransitionException(OrderStatus currentStatus, OrderStatus targetStatus) {
        super("허용되지 않은 주문 상태 전이입니다. status=" + currentStatus + ", target=" + targetStatus);
        this.currentStatus = Objects.requireNonNull(currentStatus, "currentStatus");
        this.targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
    }
}
