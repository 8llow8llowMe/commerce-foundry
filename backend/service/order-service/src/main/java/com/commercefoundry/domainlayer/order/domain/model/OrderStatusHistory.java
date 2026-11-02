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

    /** 송장 등록으로 출고됐다 — 상품 준비중 → 배송 중 (#18, delivery-service). */
    public static final String SHIPMENT_REGISTERED = "SHIPMENT_REGISTERED";
    /** 배송이 완료됐다 — 배송 중 → 배송 완료 (#18, delivery-service). */
    public static final String DELIVERY_COMPLETED = "DELIVERY_COMPLETED";
    /** 결제 승인이 반영됐다 — 결제 대기 · 만료 → 결제 완료 (#308, payment-service 의 {@code payment-approved} 이벤트 + 재조회). {@link #changedByService} 로 남긴다. */
    public static final String PAYMENT_APPROVED = "PAYMENT_APPROVED";

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

    /** 시스템의 자동 구매 확정. 배송 완료 전 주문으로 이력을 만들 수 없다. */
    public static OrderStatusHistory autoConfirmed(long id, Order order) {
        if (order.isNew() || order.status() != OrderStatus.DELIVERED || order.deliveredAt() == null) {
            throw new IllegalArgumentException("자동 구매 확정 이력은 배송 완료 시각이 있는 저장된 배송 완료 주문이어야 합니다");
        }
        return new OrderStatusHistory(id, order.id(), OrderStatus.DELIVERED, OrderStatus.PURCHASE_CONFIRMED,
            StatusChangedBy.SYSTEM, null, "AUTO_PURCHASE_CONFIRMATION");
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

    /**
     * 어드민이 상태를 바꿨다 (#17). {@code before} 는 전이 전 주문, {@code after} 는 전이 뒤 주문이다.
     *
     * @param reason 운영자가 남긴 사유(200자 이하). 없으면 null
     */
    public static OrderStatusHistory changedByAdmin(long id, Order before, Order after, long adminId, String reason) {
        if (before.isNew() || before.id() != after.id() || before.status() == after.status()) {
            throw new IllegalArgumentException("어드민 이력은 저장된 같은 주문의 상태 변경에서만 만듭니다: orderId=" + before.id());
        }
        return new OrderStatusHistory(id, before.id(), before.status(), after.status(), StatusChangedBy.ADMIN, adminId, reason);
    }

    /**
     * 다른 서비스의 internal 호출이 상태를 바꿨다 (#18 출고 · 배송 완료) — 주체 {@code SERVICE}, 아이디 없음. {@code before} 는 전이 전 주문, {@code after} 는 전이 뒤 주문이다.
     *
     * @param reason 사유 코드 — {@link #SHIPMENT_REGISTERED} · {@link #DELIVERY_COMPLETED} · {@link #PAYMENT_APPROVED}(#308)
     */
    public static OrderStatusHistory changedByService(long id, Order before, Order after, String reason) {
        Objects.requireNonNull(reason, "reason");
        if (before.isNew() || before.id() != after.id() || before.status() == after.status()) {
            throw new IllegalArgumentException("연동 이력은 저장된 같은 주문의 상태 변경에서만 만듭니다: orderId=" + before.id());
        }
        return new OrderStatusHistory(id, before.id(), before.status(), after.status(), StatusChangedBy.SERVICE, null, reason);
    }

    /** 고객이 결제 전에 취소했다 — PENDING_PAYMENT → CANCELED, 사유는 취소 사유 코드 (#16). {@code order} 는 전이 전 주문이다. */
    public static OrderStatusHistory canceledByCustomer(long id, Order order, OrderCancelReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (order.isNew() || order.status() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalArgumentException("결제 전 취소 이력은 저장된 결제 대기 주문에서만 만듭니다: orderId=" + order.id() + ", status=" + order.status());
        }
        return new OrderStatusHistory(id, order.id(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELED, StatusChangedBy.CUSTOMER, order.memberId(), reason.name());
    }

    @Override
    public String toString() {
        return "OrderStatusHistory[id=" + id + ", orderId=" + orderId + ", fromStatus=" + fromStatus
            + ", toStatus=" + toStatus + ", changedByType=" + changedByType + "]";
    }
}
