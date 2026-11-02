package com.commercefoundry.domainlayer.shipment.application.port.out;

import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 송장 · 배송 상태 이력 저장. 쓰기는 모두 이 호출에서 flush 한다 — 유일 키 · 버전 충돌이 커밋 시점이 아니라 여기서 드러나 Processor 가 도메인 오류로 바꿀 수 있게.
 */
public interface ShipmentRepositoryPort {

    /** 주문의 송장 — 주문당 1건({@code uk_shipment_order_id}). */
    Optional<Shipment> findByOrderId(long orderId);

    /**
     * 새 송장 INSERT.
     *
     * @return 저장된 송장(버전이 채워진다)
     * @throws OptimisticLockingFailureException 같은 주문의 송장이 이미 있다 — 동시 등록 경합(유일 키 · 기본 키 위반만, check 위반은 그대로 던진다)
     */
    Shipment insert(Shipment shipment);

    /**
     * 읽어 온 송장의 변경 저장 — 버전 비교({@code @Version}).
     *
     * @throws OptimisticLockingFailureException 읽은 뒤 다른 요청이 먼저 바꿨거나 지웠다
     */
    Shipment update(Shipment shipment);

    void insertHistory(ShipmentStatusHistory history);

    /**
     * 주문에 반영되지 않은 대기 송장과 그 이력을 지운다. 버전 · 반영 여부가 그대로일 때만 지운다 — 그 사이 다른 요청이 재시도로 바꿨거나 반영했으면 건드리지 않는다.
     *
     * @return 지웠으면 true
     */
    boolean deletePending(long shipmentId, long version);
}
