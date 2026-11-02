package com.commercefoundry.domainlayer.shipment.application.service.processor;

import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import com.commercefoundry.domainlayer.shipment.application.info.ShipmentDetailInfo;
import com.commercefoundry.domainlayer.shipment.application.port.out.ShipmentRepositoryPort;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 송장 읽기 (#18 PR-B2) — 어드민 상세 · 목록, 고객 배송 조회. 읽기 전용 트랜잭션이다. 호출한 Facade 가 트랜잭션 밖({@code NEVER})이어도 여기서 새로 연다.
 * 읽은 송장은 저장된 상태 그대로다 — order 의 주문 상태와 맞추지 않는다({@code order-flow-guide.md} §4 "상태가 갈라지는 경우"). 고객 응답에서 배송 완료 반영 대기를
 * 배송 중으로 보이는 것은 {@code ShipmentWebPresenter} 다.
 */
@Component
@RequiredArgsConstructor
public class ShipmentQueryProcessor {

    private final ShipmentRepositoryPort shipmentRepositoryPort;

    /**
     * 송장과 이력을 한 트랜잭션에서 읽는다.
     *
     * @throws ShipmentException {@code SHIPMENT_NOT_FOUND}(023)
     */
    @Transactional(readOnly = true)
    public ShipmentDetailInfo getDetail(long shipmentId) {
        Shipment shipment = shipmentRepositoryPort.findById(shipmentId).orElseThrow(() -> new ShipmentException(ShipmentErrorCode.SHIPMENT_NOT_FOUND));
        return new ShipmentDetailInfo(shipment, shipmentRepositoryPort.findHistories(shipmentId));
    }

    /** 어드민 목록 — 최신순. 조건 의미는 {@link ShipmentRepositoryPort#search}. */
    @Transactional(readOnly = true)
    public Page<Shipment> search(Long orderId, ShipmentStatus status, Boolean synced, int page, int size) {
        return shipmentRepositoryPort.search(orderId, status, synced, page, size);
    }

    /**
     * 고객이 볼 수 있는 주문의 송장 — 출고가 주문에 반영됐고 송장의 회원 스냅숏이 토큰의 회원과 같을 때만 있다. 소유는 delivery 가 로컬에서 판정한다(원격 호출 없음).
     * 없음 · 남의 것 · 반영 전 송장을 가르지 않는다 — 셋 다 비어 있다(주문 존재 · 소유 404 는 주문 상세의 몫, {@code api-design-guide.md} §2-2).
     */
    @Transactional(readOnly = true)
    public Optional<Shipment> findCustomerShipment(long orderId, long memberId) {
        return shipmentRepositoryPort.findByOrderId(orderId)
            .filter(Shipment::orderSynced)
            .filter(shipment -> shipment.memberId() != null && shipment.memberId() == memberId);
    }
}
