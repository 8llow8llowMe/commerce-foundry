package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import java.util.Objects;

/**
 * 주문 만료 · 결제 전 취소 이벤트의 재고 선점 해제 ({@code order-flow-guide.md} §3-5).
 *
 * @param reservationId 해제할 선점 — 해제 키다. 같은 주문의 재선점(만료 후 확정)이 늦게 온 옛 이벤트로 풀리지 않게 주문 아이디가 아니라 선점 아이디로 고른다
 * @param orderId 교차 검증 — 선점의 주문 아이디와 다르면 계약 위반이다
 * @param reason {@link StockReleaseReason#ORDER_EXPIRED} 또는 {@link StockReleaseReason#ORDER_CANCELED} — 스윕 · internal 해제의 사유는 받지 않는다
 */
public record OrderStockReleaseCommand(long reservationId, long orderId, StockReleaseReason reason) {

    public OrderStockReleaseCommand {
        Objects.requireNonNull(reason, "reason");
        if (reason != StockReleaseReason.ORDER_EXPIRED && reason != StockReleaseReason.ORDER_CANCELED) {
            throw new IllegalArgumentException("주문 이벤트의 해제 사유가 아닙니다: " + reason);
        }
    }
}
