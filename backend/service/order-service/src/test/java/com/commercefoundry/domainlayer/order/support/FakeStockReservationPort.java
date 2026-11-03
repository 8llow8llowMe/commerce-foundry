package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockConfirmationQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * product-service 선점 · 확정을 흉내 낸다. 같은 주문 아이디는 같은 활성 선점을 주고(확정된 것 포함), 다음 실패를 미리 넣을 수 있다.
 * 확정은 멱등이다(이미 확정이면 그대로). {@link #expire} 로 해제된 선점은 확정에서 {@code RELEASED} 이고 같은 주문의 재선점은 새 아이디다.
 */
public class FakeStockReservationPort implements StockReservationPort {

    public static final Instant EXPIRES_AT = Instant.parse("2026-10-18T09:30:00Z");

    private final Map<Long, StockReservationQueryResult> activeByOrderId = new HashMap<>();
    private final Map<Long, Long> orderIdByReservationId = new HashMap<>();
    private final Set<Long> confirmed = new HashSet<>();
    private final Set<Long> released = new HashSet<>();
    private final Deque<StockReservationException> reserveFailures = new ArrayDeque<>();
    private final Deque<StockReservationException> confirmFailures = new ArrayDeque<>();
    private final List<SortedMap<Long, Integer>> reserveCalls = new ArrayList<>();
    private final List<Long> confirmCalls = new ArrayList<>();
    private final Deque<StockReservationException> releaseFailures = new ArrayDeque<>();
    private final List<Long> releaseCalls = new ArrayList<>();
    private Instant expiresAt = EXPIRES_AT;
    private long nextReservationId = 880_000L;

    @Override
    public StockReservationQueryResult reserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId) {
        reserveCalls.add(new TreeMap<>(quantitiesBySkuId));
        if (!reserveFailures.isEmpty()) {
            throw reserveFailures.poll();
        }
        return activeByOrderId.computeIfAbsent(orderId, id -> {
            long reservationId = nextReservationId++;
            orderIdByReservationId.put(reservationId, id);
            return new StockReservationQueryResult(reservationId, id, expiresAt);
        });
    }

    @Override
    public StockReservationQueryResult reReserve(long orderId, SortedMap<Long, Integer> quantitiesBySkuId) {
        return reserve(orderId, quantitiesBySkuId);
    }

    @Override
    public StockConfirmationQueryResult confirm(long reservationId) {
        confirmCalls.add(reservationId);
        if (!confirmFailures.isEmpty()) {
            throw confirmFailures.poll();
        }
        if (released.contains(reservationId)) {
            throw new StockReservationException(StockReservationException.Failure.RELEASED);
        }
        Long orderId = orderIdByReservationId.get(reservationId);
        if (orderId == null) {
            throw new StockReservationException(StockReservationException.Failure.NOT_FOUND);
        }
        confirmed.add(reservationId);
        return new StockConfirmationQueryResult(reservationId, orderId);
    }

    /** 선점을 미리 만든다 — 저장된 주문의 선점 아이디를 흉내 낸다. */
    public FakeStockReservationPort existing(long orderId, long reservationId) {
        activeByOrderId.put(orderId, new StockReservationQueryResult(reservationId, orderId, expiresAt));
        orderIdByReservationId.put(reservationId, orderId);
        return this;
    }

    /**
     * 해제 프로브 (#308 PR-C1) — 확정된 선점은 {@code CONFIRMED}, 모르는 선점은 {@code NOT_FOUND}, 그 밖은 해제한다(이미 해제면 그대로).
     */
    @Override
    public void release(long reservationId, long orderId) {
        releaseCalls.add(reservationId);
        if (!releaseFailures.isEmpty()) {
            throw releaseFailures.poll();
        }
        if (confirmed.contains(reservationId)) {
            throw new StockReservationException(StockReservationException.Failure.CONFIRMED);
        }
        if (!orderIdByReservationId.containsKey(reservationId)) {
            throw new StockReservationException(StockReservationException.Failure.NOT_FOUND);
        }
        expire(reservationId);
    }

    /** 만료 스윕 · 해제 이벤트가 선점을 푼 것을 흉내 낸다 — 활성에서 빠져 같은 주문의 재선점은 새 선점이다. */
    public FakeStockReservationPort expire(long reservationId) {
        released.add(reservationId);
        Long orderId = orderIdByReservationId.get(reservationId);
        if (orderId != null && activeByOrderId.containsKey(orderId) && activeByOrderId.get(orderId).reservationId() == reservationId) {
            activeByOrderId.remove(orderId);
        }
        return this;
    }

    public FakeStockReservationPort failNextReserve(StockReservationException.Failure... failures) {
        for (StockReservationException.Failure failure : failures) {
            reserveFailures.add(new StockReservationException(failure));
        }
        return this;
    }

    public FakeStockReservationPort failNextConfirm(StockReservationException.Failure... failures) {
        for (StockReservationException.Failure failure : failures) {
            confirmFailures.add(new StockReservationException(failure));
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

    public List<Long> confirmCalls() {
        return confirmCalls;
    }

    public List<Long> releaseCalls() {
        return releaseCalls;
    }

    public FakeStockReservationPort failNextRelease(StockReservationException.Failure... failures) {
        for (StockReservationException.Failure failure : failures) {
            releaseFailures.add(new StockReservationException(failure));
        }
        return this;
    }

    public boolean isConfirmed(long reservationId) {
        return confirmed.contains(reservationId);
    }
}
