package com.commercefoundry.domainlayer.picking.domain.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 출고 묶음 요청에 들어왔지만 대상이 아니어서 뺀 주문과 그 사유. 묶음과 함께 저장해 상세 조회에서 다시 보여 준다 — 운영자가 왜 빠졌는지 알 수 있게.
 */
public record PickingExclusion(long orderId, Reason reason) {

    public PickingExclusion {
        if (orderId <= 0) {
            throw new IllegalArgumentException("주문 아이디는 양수여야 합니다.");
        }
        Objects.requireNonNull(reason, "제외 사유가 필요합니다.");
    }

    /**
     * 제외 사유. 이름은 업무 의미다 — 저장값({@code picking_order.exclusion_reason})이라 이름을 바꾸면 DDL 도 바꾼다.
     * order 의 주문 상태({@code OrderStatus}) 매핑은 {@link #ofOrderStatus(String)} 가 정본이다.
     */
    public enum Reason {
        /** 요청한 주문 아이디가 order 에 없다. */
        ORDER_NOT_FOUND("찾을 수 없는 주문"),
        /** PENDING_PAYMENT. */
        PAYMENT_PENDING("결제 전 주문"),
        /** EXPIRED · CANCEL_REQUESTED · CANCELED. */
        CANCELED_OR_EXPIRED("취소 · 만료된 주문"),
        /** SHIPPING · DELIVERED · PURCHASE_CONFIRMED · RETURN_REQUESTED · RETURNED — 이미 출고가 끝났다. */
        ALREADY_SHIPPED("이미 출고된 주문"),
        /** PAID · MAKING · 모르는 상태 — 아직 상품 준비 중이 아니다. */
        NOT_READY_TO_SHIP("상품 준비 중이 아닌 주문"),
        /** 배송 국가가 KR 이 아니거나 없다. */
        OVERSEAS_SHIPPING("해외 배송 주문"),
        /** 제작 상품이 한 줄이라도 있다. */
        CUSTOM_ORDER_INCLUDED("제작 상품이 포함된 주문"),
        /** 주문 항목이 없다. */
        NO_ORDER_ITEMS("상품이 없는 주문");

        private static final String READY_TO_SHIP = "PREPARING";

        private static final Map<String, Reason> BY_ORDER_STATUS = Map.ofEntries(
            Map.entry("PENDING_PAYMENT", PAYMENT_PENDING),
            Map.entry("EXPIRED", CANCELED_OR_EXPIRED),
            Map.entry("CANCEL_REQUESTED", CANCELED_OR_EXPIRED),
            Map.entry("CANCELED", CANCELED_OR_EXPIRED),
            Map.entry("SHIPPING", ALREADY_SHIPPED),
            Map.entry("DELIVERED", ALREADY_SHIPPED),
            Map.entry("PURCHASE_CONFIRMED", ALREADY_SHIPPED),
            Map.entry("RETURN_REQUESTED", ALREADY_SHIPPED),
            Map.entry("RETURNED", ALREADY_SHIPPED)
        );

        private final String displayName;

        Reason(String displayName) {
            this.displayName = displayName;
        }

        /** 운영자 화면에 쓰는 이름. */
        public String displayName() {
            return displayName;
        }

        /** 주문 상태로 정해지는 제외 사유. 상품 준비 중(PREPARING)이면 비어 있다. 표에 없는 상태(PAID · MAKING · 새로 생긴 상태)는 {@link #NOT_READY_TO_SHIP} 이다. */
        static Optional<Reason> ofOrderStatus(String orderStatus) {
            if (READY_TO_SHIP.equals(orderStatus)) {
                return Optional.empty();
            }
            return Optional.of(BY_ORDER_STATUS.getOrDefault(orderStatus, NOT_READY_TO_SHIP));
        }
    }
}
