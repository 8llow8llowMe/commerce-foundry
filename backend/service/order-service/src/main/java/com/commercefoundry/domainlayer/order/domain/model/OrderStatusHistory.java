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
        if (id <= 0 || orderId <= 0 || (changedById != null && changedById <= 0) || (reason != null && reason.length() > 200)) {
            throw new IllegalArgumentException("Invalid history identifier or reason length");
        }
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedByType, "changedByType");
        if (changedByType.hasActorId() != (changedById != null)) {
            throw new IllegalArgumentException("바꾼 주체 아이디는 CUSTOMER · ADMIN 에만 있습니다: changedByType=" + changedByType);
        }
    }

    /** 고객이 주문을 만들었다 — null → PENDING_PAYMENT. */
    public static OrderStatusHistory created(long id, Order order) {
        if (!order.isNew() || order.createdAt() != null || order.status() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalArgumentException("Creation history requires a new pending order");
        }
        return new OrderStatusHistory(id, order.id(), null, order.status(), StatusChangedBy.CUSTOMER, order.memberId(), null);
    }

    /** 결제 기한이 지나 시스템이 만료했다 — PENDING_PAYMENT → EXPIRED, 사유 {@code PAYMENT_TIMEOUT}. {@code order} 는 전이 전 주문이다. */
    public static OrderStatusHistory expired(long id, Order order) {
        if (order.isNew() || order.status() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalArgumentException("만료 이력은 저장된 결제 대기 주문에서만 만듭니다: orderId=" + order.id() + ", status=" + order.status());
        }
        return new OrderStatusHistory(id, order.id(), OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED, StatusChangedBy.SYSTEM, null, "PAYMENT_TIMEOUT");
    }

    @Override
    public String toString() {
        return "OrderStatusHistory[id=" + id + ", orderId=" + orderId + ", fromStatus=" + fromStatus
            + ", toStatus=" + toStatus + ", changedByType=" + changedByType + "]";
    }
}
