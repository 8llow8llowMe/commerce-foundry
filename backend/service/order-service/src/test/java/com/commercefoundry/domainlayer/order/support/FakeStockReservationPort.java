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
 * product-service 선점을 흉내 낸다. 같은 주문 아이디는 같은 결과를 주고 다음 실패를 미리 넣을 수 있다.
 */
public class FakeStockReservationPort implements StockReservationPort {

    public static final Instant EXPIRES_AT = Instant.parse("2026-10-18T09:30:00Z");

    private final Map<Long, StockReservationQueryResult> activeByOrderId = new HashMap<>();
    private final Deque<StockReservationException> reserveFailures = new ArrayDeque<>();
    private final List<SortedMap<Long, Integer>> reserveCalls = new ArrayList<>();
    private Instant expiresAt = EXPIRES_AT;
    private long nextReservationId = 880_000L;

    @Override
    public StockReservationQueryResult reserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId) {
        reserveCalls.add(new TreeMap<>(quantitiesBySkuId));
        if (!reserveFailures.isEmpty()) {
            throw reserveFailures.poll();
        }
        return activeByOrderId.computeIfAbsent(orderId, id -> new StockReservationQueryResult(nextReservationId++, id, expiresAt));
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

    public List<SortedMap<Long, Integer>> reserveCalls() {
        return reserveCalls;
    }

}
