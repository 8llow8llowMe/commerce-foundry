package com.commercefoundry.domainlayer.shipment.support;

import com.commercefoundry.domainlayer.shipment.application.port.out.ShipmentRepositoryPort;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 송장 저장소 테스트 대역 — 실제 어댑터와 같은 계약(주문당 1건 · 버전 비교 · 조건부 삭제 · 이력 시간순)을 메모리에서 지킨다. Facade · Processor 의 실패 표 경로를
 * DB 없이 여러 단계에 걸쳐 본다. 실제 SQL 은 {@code ShipmentRepositoryAdapterTest}(PostgreSQL) · {@code ShipmentRegisterFlowTest} · {@code ShipmentStatusFlowTest}(H2) 가 본다.
 */
public class InMemoryShipmentRepository implements ShipmentRepositoryPort {

    /** 이력의 바뀐 시각 — 저장 때 감사 리스너가 채우는 값을 흉내 낸다. */
    private static final Instant SAVED_AT = Instant.parse("2026-11-02T00:00:00Z");

    private final Map<Long, Shipment> shipmentsById = new LinkedHashMap<>();
    private final List<ShipmentStatusHistory> histories = new ArrayList<>();

    @Override
    public Optional<Shipment> findByOrderId(long orderId) {
        return shipmentsById.values().stream().filter(shipment -> shipment.orderId() == orderId).findFirst();
    }

    @Override
    public Optional<Shipment> findById(long shipmentId) {
        return Optional.ofNullable(shipmentsById.get(shipmentId));
    }

    @Override
    public Page<Shipment> search(Long orderId, ShipmentStatus status, Boolean synced, int page, int size) {
        List<Shipment> matched = shipmentsById.values().stream()
            .filter(shipment -> orderId == null || shipment.orderId() == orderId)
            .filter(shipment -> status == null || shipment.status() == status)
            .filter(shipment -> synced == null || synced == (shipment.orderSynced() && !shipment.completionPending()))
            .sorted(Comparator.comparingLong(Shipment::id).reversed())
            .toList();
        int from = Math.min(page * size, matched.size());
        return new PageImpl<>(matched.subList(from, Math.min(from + size, matched.size())), PageRequest.of(page, size), matched.size());
    }

    @Override
    public List<ShipmentStatusHistory> findHistories(long shipmentId) {
        return histories.stream().filter(history -> history.shipmentId() == shipmentId).sorted(Comparator.comparingLong(ShipmentStatusHistory::id)).toList();
    }

    @Override
    public Shipment insert(Shipment shipment) {
        if (findByOrderId(shipment.orderId()).isPresent() || shipmentsById.containsKey(shipment.id())) {
            throw new OptimisticLockingFailureException("duplicate order");
        }
        return put(shipment, 0L);
    }

    @Override
    public Shipment update(Shipment shipment) {
        Shipment current = shipmentsById.get(shipment.id());
        if (current == null || !current.version().equals(shipment.version())) {
            throw new OptimisticLockingFailureException("stale shipment");
        }
        return put(shipment, shipment.version() + 1);
    }

    @Override
    public void insertHistory(ShipmentStatusHistory history) {
        histories.add(new ShipmentStatusHistory(
            history.id(), history.shipmentId(), history.fromStatus(), history.toStatus(), history.changedBy(), history.changedById(), history.reason(), SAVED_AT));
    }

    @Override
    public boolean deletePending(long shipmentId, long version) {
        Shipment current = shipmentsById.get(shipmentId);
        if (current == null || current.orderSynced() || current.version() != version) {
            return false;
        }
        shipmentsById.remove(shipmentId);
        histories.removeIf(history -> history.shipmentId() == shipmentId);
        return true;
    }

    /** 다른 요청이 그 사이 같은 송장을 바꾼 것처럼 버전만 올린다. */
    public void bumpVersion(long shipmentId) {
        Shipment current = shipmentsById.get(shipmentId);
        put(current, current.version() + 1);
    }

    /** 다른 요청이 그 사이 같은 송장을 바꾼 것처럼 저장된 행을 {@code changed} 로 덮고 버전을 올린다. */
    public void overwrite(Shipment changed) {
        Shipment current = shipmentsById.get(changed.id());
        put(changed, current.version() + 1);
    }

    /** 다른 요청이 그 사이 대기 송장을 지운 것처럼 송장 · 이력을 지운다. */
    public void remove(long shipmentId) {
        shipmentsById.remove(shipmentId);
        histories.removeIf(history -> history.shipmentId() == shipmentId);
    }

    public Optional<Shipment> byOrderId(long orderId) {
        return findByOrderId(orderId);
    }

    public List<ShipmentStatusHistory> histories() {
        return List.copyOf(histories);
    }

    public int size() {
        return shipmentsById.size();
    }

    private Shipment put(Shipment shipment, long version) {
        Shipment saved = new Shipment(
            shipment.id(), shipment.orderId(), shipment.memberId(), shipment.carrier(), shipment.trackingNumber(), shipment.status(),
            shipment.shippingScope(), shipment.orderSynced(), shipment.completionPending(), shipment.previousStatus(), shipment.shippedAt(), shipment.deliveredAt(),
            version
        );
        shipmentsById.put(saved.id(), saved);
        return saved;
    }
}
