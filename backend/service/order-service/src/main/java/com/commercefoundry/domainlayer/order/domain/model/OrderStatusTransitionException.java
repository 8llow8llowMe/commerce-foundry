package com.commercefoundry.domainlayer.order.domain.model;

import java.util.Objects;
import lombok.Getter;

/**
 * 주문 상태의 허용 전이 표({@link OrderStatus}) 밖 요청.
 *
 * <p>domain 소유 예외다. domain 은 application 의 오류 코드를 모르므로 현재 상태와 시도한 상태만 싣고, 업무 오류 코드
 * ({@code ORDER_STATUS_TRANSITION_INVALID} — {@code ORDER_020}, 409)는 상태를 바꾸는 쪽(결제 완료 #14 · 만료 스윕 #96 · 클레임)의 Processor 가 이 예외를 받아 붙인다
 * ({@code coding-conventions.md} §8-1). 지금은 그런 Processor 가 없어 코드는 예약이다. 핸들러는 이 예외를 받지 않는다 — 번역하지 않고 흘리면 500 이다.
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
