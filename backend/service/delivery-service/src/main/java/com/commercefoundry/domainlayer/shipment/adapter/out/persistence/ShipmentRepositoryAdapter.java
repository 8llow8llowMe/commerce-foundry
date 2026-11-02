package com.commercefoundry.domainlayer.shipment.adapter.out.persistence;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentEntity;
import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository.ShipmentRepository;
import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository.ShipmentStatusHistoryRepository;
import com.commercefoundry.domainlayer.shipment.application.mapper.ShipmentMapper;
import com.commercefoundry.domainlayer.shipment.application.port.out.ShipmentRepositoryPort;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShipmentRepositoryAdapter implements ShipmentRepositoryPort {

    /** 같은 주문의 동시 등록이 부딪히는 제약 — 주문 유일 키와 PostgreSQL 기본 키({@code <테이블>_pkey}). 소문자 · 스키마 없는 이름이다. */
    private static final Set<String> DUPLICATE_SHIPMENT_CONSTRAINTS = Set.of("uk_shipment_order_id", "shipment_pkey");

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStatusHistoryRepository shipmentStatusHistoryRepository;
    private final ShipmentMapper shipmentMapper;

    @Override
    public Optional<Shipment> findByOrderId(long orderId) {
        return shipmentRepository.findByOrderId(orderId).map(shipmentMapper::toDomain);
    }

    /**
     * 유일 키 · 기본 키 위반만 버전 충돌과 같은 신호로 알린다 — 제약 이름은 persistence-core {@link ConstraintViolations} 로 정확히 비교한다(#247).
     * check · NOT NULL 위반은 코드 오류라 그대로 던진다 — 409 로 바꾸면 버그가 "다시 시도하세요" 뒤에 숨는다.
     */
    @Override
    public Shipment insert(Shipment shipment) {
        if (shipment.version() != null) {
            throw new IllegalArgumentException("새 송장은 버전이 없다: id=" + shipment.id());
        }
        try {
            return shipmentMapper.toDomain(shipmentRepository.saveAndFlush(shipmentMapper.toEntity(shipment)));
        } catch (DataIntegrityViolationException exception) {
            if (!isDuplicateShipment(exception)) {
                throw exception;
            }
            throw new ObjectOptimisticLockingFailureException(ShipmentEntity.class, shipment.id(), "shipment already registered for the order", exception);
        }
    }

    static boolean isDuplicateShipment(DataIntegrityViolationException exception) {
        String constraintName = ConstraintViolations.constraintNameOf(exception);
        return constraintName != null && DUPLICATE_SHIPMENT_CONSTRAINTS.contains(constraintName);
    }

    /** merge — 읽은 버전과 다르거나 행이 없으면 {@link ObjectOptimisticLockingFailureException} 이다. 바로 flush 해 이 호출에서 드러나게 한다. */
    @Override
    public Shipment update(Shipment shipment) {
        if (shipment.version() == null) {
            throw new IllegalArgumentException("읽어 온 송장만 바꾼다: id=" + shipment.id());
        }
        return shipmentMapper.toDomain(shipmentRepository.saveAndFlush(shipmentMapper.toEntity(shipment)));
    }

    @Override
    public void insertHistory(ShipmentStatusHistory history) {
        shipmentStatusHistoryRepository.saveAndFlush(shipmentMapper.toEntity(history));
    }

    /** 송장 조건부 삭제가 1건일 때만 이력을 지운다 — 남겨진(반영 · 재시도된) 송장의 이력은 건드리지 않는다. */
    @Override
    public boolean deletePending(long shipmentId, long version) {
        if (shipmentRepository.deletePending(shipmentId, version) == 0) {
            return false;
        }
        shipmentStatusHistoryRepository.deleteAllByShipmentId(shipmentId);
        return true;
    }
}
