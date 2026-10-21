package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 요청 기록 포트의 계약을 메모리로 흉내 낸다 — (회원, 키) 유니크, 버전이 같을 때만 저장하고 버전을 올린다(@Version).
 * 트랜잭션 롤백은 {@link #snapshot()} · {@link #restore} 로 흉내 낸다.
 */
public class InMemoryOrderRequestRepository implements OrderRequestRepositoryPort {

    private final Map<String, OrderRequest> rows = new HashMap<>();

    @Override
    public Optional<OrderRequest> insertIfAbsent(OrderRequest request) {
        String key = key(request.memberId(), request.idempotencyKey());
        if (rows.containsKey(key)) {
            return Optional.empty();
        }
        OrderRequest saved = withVersion(request, 0L);
        rows.put(key, saved);
        return Optional.of(saved);
    }

    @Override
    public Optional<OrderRequest> findByMemberIdAndIdempotencyKey(long memberId, UUID idempotencyKey) {
        return Optional.ofNullable(rows.get(key(memberId, idempotencyKey)));
    }

    @Override
    public Optional<OrderRequest> saveIfUnchanged(OrderRequest request) {
        String key = key(request.memberId(), request.idempotencyKey());
        OrderRequest stored = rows.get(key);
        if (stored == null || !stored.version().equals(request.version())) {
            return Optional.empty();
        }
        OrderRequest saved = withVersion(request, request.version() + 1);
        rows.put(key, saved);
        return Optional.of(saved);
    }

    /** 테스트 준비 — 이미 있는 행 (버전 그대로). */
    public void givenSaved(OrderRequest request) {
        rows.put(key(request.memberId(), request.idempotencyKey()), request);
    }

    public List<OrderRequest> all() {
        return List.copyOf(rows.values());
    }

    public Map<String, OrderRequest> snapshot() {
        return Map.copyOf(rows);
    }

    public void restore(Map<String, OrderRequest> snapshot) {
        rows.clear();
        rows.putAll(snapshot);
    }

    private static OrderRequest withVersion(OrderRequest row, long version) {
        return new OrderRequest(row.id(), row.memberId(), row.idempotencyKey(), row.requestHash(), row.orderId(), row.status(), row.leaseExpiresAt(), version);
    }

    private static String key(long memberId, UUID idempotencyKey) {
        return memberId + ":" + idempotencyKey;
    }
}
