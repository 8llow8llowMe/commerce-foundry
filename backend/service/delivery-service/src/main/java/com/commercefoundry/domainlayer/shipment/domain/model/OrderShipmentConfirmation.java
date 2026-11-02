package com.commercefoundry.domainlayer.shipment.domain.model;

import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.Objects;

/**
 * order-service 가 출고를 받아들였다는 답 (200, {@code api-design-guide.md} §2-18). 송장의 회원 · 배송 범위 스냅숏 원천이다.
 *
 * @param orderStatus 반영 뒤 주문 상태 제어 코드 — 판정용이 아니라 정보다(멱등이면 구매 확정 · 반품일 수 있다)
 * @param shippingScope 배송 국가로 정한 범위. 국가 코드를 읽지 못했으면 null
 */
public record OrderShipmentConfirmation(long orderId, long memberId, String orderStatus, ShippingScope shippingScope) {

    /** 출고 반영 직후의 주문 상태. 이 밖이면(배송 완료 · 구매 확정 · 반품 등) 이미 그 뒤로 지나간 주문의 멱등 응답이다. */
    public static final String SHIPPING_STATUS = "SHIPPING";

    public OrderShipmentConfirmation {
        Objects.requireNonNull(orderStatus, "orderStatus");
        if (orderId <= 0 || memberId <= 0) {
            throw new IllegalArgumentException("주문 · 회원 아이디는 양수여야 합니다: orderId=%d, memberId=%d".formatted(orderId, memberId));
        }
    }

    /** 주문이 이미 배송 중 다음 단계(배송 완료 · 구매 확정 · 반품 등)에 있는가. 송장 상태는 자동으로 맞추지 않는다 — 경고만 남기고,
     * 운영자가 배송 완료를 보내면 order 멱등 200 으로 수렴한다 ({@code order-flow-guide.md} §4 "상태가 갈라지는 경우"). */
    public boolean isPastShipping() {
        return !SHIPPING_STATUS.equals(orderStatus);
    }
}
