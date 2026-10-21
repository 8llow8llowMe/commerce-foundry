package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * product-service 재고 선점을 흉내 낸다 — 실제 계약처럼 같은 주문 아이디의 <b>활성</b> 선점이 있으면 그 선점을 돌려주고(업무 키 멱등),
 * 해제된 뒤 같은 주문 아이디로 다시 선점하면 새 선점을 준다. 다음 호출들의 실패를 차례로 미리 넣을 수 있다.
 */
public class FakeStockReservationPort implements StockReservationPort {

    public static final Instant EXPIRES_AT = Instant.parse("2026-10-18T09:30:00Z");

    private final Map<Long, StockReservationQueryResult> activeByOrderId = new HashMap<>();
    private final Deque<StockReservationException> reserveFailures = new ArrayDeque<>();
    private final List<SortedMap<Long, Integer>> reserveCalls = new ArrayList<>();
    private final List<Long> releaseCalls = new ArrayList<>();
    private Instant expiresAt = EXPIRES_AT;
    private long nextReservationId = 880_000L;
    private Runnable onReserve = () -> {
    };

    @Override
    public StockReservationQueryResult reserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId) {
        reserveCalls.add(new TreeMap<>(quantitiesBySkuId));
        onReserve.run();
        if (!reserveFailures.isEmpty()) {
            throw reserveFailures.poll();
        }
        return activeByOrderId.computeIfAbsent(orderId, id -> new StockReservationQueryResult(nextReservationId++, id, expiresAt));
    }

    @Override
    public void release(long reservationId) {
        releaseCalls.add(reservationId);
        activeByOrderId.values().removeIf(reservation -> reservation.reservationId() == reservationId);
    }

    public FakeStockReservationPort failNextReserve(StockReservationException.Failure... failures) {
        for (StockReservationException.Failure failure : failures) {
            reserveFailures.add(new StockReservationException(failure));
        }
        return this;
    }

    /** 다음 선점의 만료 시각 — 과거 기한 응답을 흉내 낸다. */
    public FakeStockReservationPort expiresAt(Instant newExpiresAt) {
        this.expiresAt = newExpiresAt;
        return this;
    }

    /** 선점 호출 중에 일어나는 일 — 다른 시도의 기록 이어받기를 흉내 낸다. */
    public FakeStockReservationPort onReserve(Runnable action) {
        this.onReserve = action;
        return this;
    }

    public List<SortedMap<Long, Integer>> reserveCalls() {
        return reserveCalls;
    }

    public List<Long> releaseCalls() {
        return releaseCalls;
    }
}
