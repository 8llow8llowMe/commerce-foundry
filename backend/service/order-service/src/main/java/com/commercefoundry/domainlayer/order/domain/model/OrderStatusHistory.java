package com.commercefoundry.domainlayer.order.domain.model;

import java.util.Objects;

/**
 * 주문 상태 변경 이력 한 줄 — 추가만 한다 ({@code order-flow-guide.md} §1-1). 언제는 행의 생성 시각이다.
 *
 * @param fromStatus 이전 상태. 주문 생성이면 null
 * @param changedById 바꾼 회원 · 관리자 아이디. SYSTEM · SERVICE 면 null
 * @param reason 사유. 없으면 null
 */
public record OrderStatusHistory(
    long id, long orderId, OrderStatus fromStatus, OrderStatus toStatus, StatusChangedBy changedByType, Long changedById, String reason
) {

    public OrderStatusHistory {
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedByType, "changedByType");
    }

    /** 고객이 주문을 만들었다 — null → PENDING_PAYMENT. */
    public static OrderStatusHistory created(long id, Order order) {
        return new OrderStatusHistory(id, order.id(), null, order.status(), StatusChangedBy.CUSTOMER, order.memberId(), null);
    }
}
