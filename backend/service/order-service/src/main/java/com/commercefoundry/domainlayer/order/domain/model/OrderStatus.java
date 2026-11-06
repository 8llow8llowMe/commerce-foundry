package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 주문 상태 ({@code order-flow-guide.md} §1). 허용 전이 표는 이 enum 이 갖고, 상태는 {@link Order} 의 도메인 메서드로만 바꾼다.
 *
 * <table>
 *   <caption>허용 전이 (표 밖은 {@link OrderStatusTransitionException})</caption>
 *   <tr><th>현재</th><th>다음</th></tr>
 *   <tr><td>PENDING_PAYMENT</td><td>PAID · EXPIRED · CANCELED — 결제 전 고객 취소 (#16)</td></tr>
 *   <tr><td>EXPIRED</td><td>PAID — 만료 뒤 결제가 완료되고 재고를 다시 잡았을 때 (#14)</td></tr>
 *   <tr><td>PAID</td><td>MAKING · PREPARING · CANCEL_REQUESTED</td></tr>
 *   <tr><td>MAKING</td><td>PREPARING · CANCEL_REQUESTED</td></tr>
 *   <tr><td>PREPARING</td><td>SHIPPING · CANCEL_REQUESTED</td></tr>
 *   <tr><td>SHIPPING</td><td>DELIVERED</td></tr>
 *   <tr><td>DELIVERED</td><td>PURCHASE_CONFIRMED · RETURN_REQUESTED</td></tr>
 *   <tr><td>CANCEL_REQUESTED</td><td>CANCELED · PAID · MAKING · PREPARING — 뒤의 셋은 운영자 취소 거절로 요청 전 상태에 돌아갈 때만
 *       ({@link Order#rejectCancellation}, #17). 어드민 상태 변경 화면의 전이가 아니다</td></tr>
 *   <tr><td>RETURN_REQUESTED</td><td>RETURNED</td></tr>
 * </table>
 */
@Getter
@RequiredArgsConstructor
public enum OrderStatus implements CodeNameDescribable {

    PENDING_PAYMENT("결제 대기", "주문이 접수되었고 결제를 기다리고 있습니다."),
    EXPIRED("주문 만료", "결제 기한이 지나 주문이 만료되었습니다."),
    PAID("결제 완료", "결제가 완료되었습니다."),
    MAKING("제작 중", "주문하신 상품을 제작하고 있습니다."),
    PREPARING("상품 준비중", "결제가 완료되어 출고를 준비하고 있습니다."),
    SHIPPING("배송 중", "상품이 출고되어 배송 중입니다."),
    DELIVERED("배송 완료", "상품이 배송되었습니다."),
    PURCHASE_CONFIRMED("구매 확정", "구매가 확정되었습니다."),
    CANCEL_REQUESTED("취소 요청", "주문 취소를 요청했습니다."),
    CANCELED("취소 완료", "주문이 취소되었습니다."),
    RETURN_REQUESTED("반품 요청", "반품을 요청했습니다."),
    RETURNED("반품 완료", "반품이 완료되었습니다.");

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = transitions();

    private final String displayName;
    private final String description;

    /** 표에 있는 전이인가. */
    public boolean canTransitionTo(OrderStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    /**
     * 고객이 지금 취소를 요청할 수 있는가 (#16) — 고객 취소 판정의 정본이다(주문 내역 · 상세의 {@code cancelable}, 결제 후 취소 접수).
     * 결제 대기는 늘 된다(결제 전 취소). 결제 완료 · 상품 준비중 · 제작 중(설정)은 결제 후 취소가 열려 있을 때만이다.
     *
     * @param allowWhileMaking {@code order.cancellation.allow-while-making} — 제작에 들어간 맞춤 상품의 취소를 고객에게 열지
     * @param paidCancellationOpen 결제 후 취소를 받을 수 있는가 — 환불 · 복원 재시도가 돌 때만 연다({@code order.cancellation.retry.enabled})
     */
    public boolean isCancelableByCustomer(boolean allowWhileMaking, boolean paidCancellationOpen) {
        return switch (this) {
            case PENDING_PAYMENT -> true;
            case PAID, PREPARING -> paidCancellationOpen;
            case MAKING -> paidCancellationOpen && allowWhileMaking;
            default -> false;
        };
    }

    /** 이 상태에서 갈 수 있는 상태 — 바꿀 수 없다. */
    public Set<OrderStatus> nextStatuses() {
        return TRANSITIONS.get(this);
    }

    private static Map<OrderStatus, Set<OrderStatus>> transitions() {
        Map<OrderStatus, Set<OrderStatus>> table = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : values()) {
            table.put(status, Collections.unmodifiableSet(EnumSet.noneOf(OrderStatus.class)));
        }
        table.put(PENDING_PAYMENT, Collections.unmodifiableSet(EnumSet.of(PAID, EXPIRED, CANCELED)));
        table.put(EXPIRED, Collections.unmodifiableSet(EnumSet.of(PAID)));
        table.put(PAID, Collections.unmodifiableSet(EnumSet.of(MAKING, PREPARING, CANCEL_REQUESTED)));
        table.put(MAKING, Collections.unmodifiableSet(EnumSet.of(PREPARING, CANCEL_REQUESTED)));
        table.put(PREPARING, Collections.unmodifiableSet(EnumSet.of(SHIPPING, CANCEL_REQUESTED)));
        table.put(SHIPPING, Collections.unmodifiableSet(EnumSet.of(DELIVERED)));
        table.put(DELIVERED, Collections.unmodifiableSet(EnumSet.of(PURCHASE_CONFIRMED, RETURN_REQUESTED)));
        table.put(CANCEL_REQUESTED, Collections.unmodifiableSet(EnumSet.of(CANCELED, PAID, MAKING, PREPARING)));
        table.put(RETURN_REQUESTED, Collections.unmodifiableSet(EnumSet.of(RETURNED)));
        return Collections.unmodifiableMap(table);
    }
}
