package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.application.model.OrderStatusFilter;
import java.time.Instant;
import java.util.List;

/**
 * 어드민 주문 목록 조건 (#17). 기간은 주문 시각 {@code [from, to)} 이고, 비어 있으면 그쪽 끝이 열려 있다.
 *
 * @param status 상태. null 이면 전체
 */
public record OrderAdminSearchCommand(OrderStatusFilter status, Instant from, Instant to, int page, int size) {

    /** 열린 기간의 양 끝 — 쿼리를 조건 조합마다 나누지 않으려 실제 시각으로 채운다. */
    private static final Instant OPEN_FROM = Instant.EPOCH;
    private static final Instant OPEN_TO = Instant.parse("9999-12-31T00:00:00Z");

    public OrderAdminSearchCommand {
        if (from != null && to != null && !from.isBefore(to)) {
            String[] message = OrderValidationMessage.PERIOD_INVALID.split(":", 2);
            throw new OrderFieldValidationException(List.of(new OrderFieldValidationException.Violation(message[0], "from", message[1])));
        }
    }

    public Instant fromOrOpen() {
        return from == null ? OPEN_FROM : from;
    }

    public Instant toOrOpen() {
        return to == null ? OPEN_TO : to;
    }
}
