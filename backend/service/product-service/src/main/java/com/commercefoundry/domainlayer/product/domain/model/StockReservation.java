package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 주문 하나의 재고 선점 (헤더 + 항목). 상태는 {@link #confirm} · {@link #release} 의 허용 전이 표로만 바뀐다 — setter 를 두지 않는다
 * ({@code order-flow-guide.md} §3). 전이는 새 인스턴스를 만들고, 바뀌었는지는 {@link Transition#changed()} 로 알린다.
 *
 * <table>
 *   <caption>허용 전이 (표 밖은 409)</caption>
 *   <tr><th>현재</th><th>confirm</th><th>release</th></tr>
 *   <tr><td>RESERVED (만료 시각 전 · 후 모두)</td><td>CONFIRMED</td><td>RELEASED</td></tr>
 *   <tr><td>CONFIRMED</td><td>그대로 (멱등)</td><td>{@code STOCK_RESERVATION_CONFIRMED}</td></tr>
 *   <tr><td>RELEASED</td><td>{@code STOCK_RESERVATION_RELEASED}</td><td>그대로 (멱등)</td></tr>
 * </table>
 *
 * <p>만료 시각이 지났어도 스케줄러가 풀기 전이면 RESERVED 이고 확정할 수 있다 — 재고는 아직 잡혀 있다. 동시에 확정 · 만료가 오면
 * {@code @Version} 이 하나만 통과시킨다.
 *
 * @param orderId 주문 아이디 (order-service). 주문당 활성(RESERVED · CONFIRMED) 선점은 하나다 — {@link #activeOrderId()}
 * @param expiresAt 결제 없이 이 시각이 지나면 만료 대상이다
 * @param confirmedAt CONFIRMED 일 때만 있다
 * @param releasedAt RELEASED 일 때만 있다
 * @param releaseReason RELEASED 일 때만 있다
 * @param items SKU 아이디 오름차순, SKU 마다 한 건
 * @param version 낙관적 락 버전. 아직 저장하지 않은 선점은 null 이다
 */
public record StockReservation(
    long id, long orderId, StockReservationStatus status, Instant expiresAt,
    Instant confirmedAt, Instant releasedAt, StockReleaseReason releaseReason,
    List<StockReservationItem> items, Long version
) {

    public StockReservation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("선점 항목이 없습니다.");
        }
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i - 1).skuId() >= items.get(i).skuId()) {
                throw new IllegalArgumentException("선점 항목은 SKU 아이디 오름차순이고 SKU 마다 한 건이어야 합니다.");
            }
        }
        if ((status == StockReservationStatus.CONFIRMED) != (confirmedAt != null)) {
            throw new IllegalArgumentException("확정 시각은 CONFIRMED 일 때만 있어야 합니다: " + status);
        }
        if ((status == StockReservationStatus.RELEASED) != (releasedAt != null && releaseReason != null)) {
            throw new IllegalArgumentException("해제 시각 · 사유는 RELEASED 일 때만 있어야 합니다: " + status);
        }
        items = List.copyOf(items);
    }

    /** 새 선점. 저장 전이라 버전이 없다 — 첫 저장은 INSERT 다. */
    public static StockReservation reserve(long id, long orderId, List<StockReservationItem> items, Instant expiresAt) {
        return new StockReservation(id, orderId, StockReservationStatus.RESERVED, expiresAt, null, null, null, items, null);
    }

    /**
     * 주문당 활성 선점을 하나로 막는 유니크 키 값. RESERVED · CONFIRMED 동안은 주문 아이디이고 RELEASED 면 null 이다 —
     * 해제된 주문은 다시 선점할 수 있다 (만료 후 결제 완료 시 재선점).
     */
    public Long activeOrderId() {
        return status == StockReservationStatus.RELEASED ? null : orderId;
    }

    /**
     * 결제 완료로 확정한다.
     *
     * @throws ProductException {@code STOCK_RESERVATION_RELEASED} — 이미 해제 · 만료된 선점. 호출한 쪽이 새로 선점한다
     */
    public Transition confirm(Instant now) {
        Objects.requireNonNull(now, "now");
        return switch (status) {
            case RESERVED -> Transition.changed(new StockReservation(
                id, orderId, StockReservationStatus.CONFIRMED, expiresAt, now, null, null, items, version
            ));
            case CONFIRMED -> Transition.unchanged(this);
            case RELEASED -> throw new ProductException(ProductErrorCode.STOCK_RESERVATION_RELEASED);
        };
    }

    /**
     * 선점을 푼다. 바뀌었으면 호출한 쪽이 {@link #deductedItems()} 의 재고를 되돌린다.
     *
     * @throws ProductException {@code STOCK_RESERVATION_CONFIRMED} — 이미 확정된 선점. 결제 후 취소 · 반품의 재고 복원은 이 경로가 아니다
     */
    public Transition release(Instant now, StockReleaseReason reason) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(reason, "reason");
        return switch (status) {
            case RESERVED -> Transition.changed(new StockReservation(
                id, orderId, StockReservationStatus.RELEASED, expiresAt, null, now, reason, items, version
            ));
            case CONFIRMED -> throw new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFIRMED);
            case RELEASED -> Transition.unchanged(this);
        };
    }

    /** 만료 스케줄러가 풀 대상인가 — RESERVED 이고 만료 시각이 지났다(같은 시각 포함). */
    public boolean isExpired(Instant now) {
        return status == StockReservationStatus.RESERVED && !expiresAt.isAfter(now);
    }

    /** 선점 때 재고를 실제로 차감한 항목. 해제 · 만료 때 이 항목만 되돌린다 — SKU 아이디 오름차순이다. */
    public List<StockReservationItem> deductedItems() {
        return items.stream().filter(StockReservationItem::deducted).toList();
    }

    /** 같은 주문의 재요청인지 — SKU 별 수량이 정확히 같은가. */
    public boolean hasSameQuantities(Map<Long, Integer> quantitiesBySkuId) {
        Map<Long, Integer> mine = items.stream().collect(Collectors.toMap(StockReservationItem::skuId, StockReservationItem::quantity));
        return mine.equals(quantitiesBySkuId);
    }

    /**
     * 전이 결과.
     *
     * @param reservation 전이 뒤 선점. 바뀌지 않았으면 원래 선점이다
     * @param changed 상태가 바뀌었는가 — false 면 이미 그 상태였다(멱등). 저장 · 재고 복원을 하지 않는다
     */
    public record Transition(StockReservation reservation, boolean changed) {

        static Transition changed(StockReservation reservation) {
            return new Transition(reservation, true);
        }

        static Transition unchanged(StockReservation reservation) {
            return new Transition(reservation, false);
        }
    }
}
