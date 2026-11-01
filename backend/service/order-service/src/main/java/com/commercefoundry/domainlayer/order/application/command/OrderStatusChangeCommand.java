package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.util.Objects;

/**
 * 어드민 주문 상태 변경 (#17). 바꾼 관리자는 access 토큰의 memberId 로만 받는다 — 본문으로 받지 않는다.
 *
 * @param reason 운영자가 남기는 사유(앞뒤 공백을 자른 200자 이하). 비면 null
 */
public record OrderStatusChangeCommand(OrderStatus status, String reason) {

    public OrderStatusChangeCommand {
        Objects.requireNonNull(status, "status");
        reason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** 사유는 자유 입력이라 로그로 새지 않게 뺀다. */
    @Override
    public String toString() {
        return "OrderStatusChangeCommand[status=" + status + ", reason=" + (reason == null ? "-" : "***") + "]";
    }
}
