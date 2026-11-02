package com.commercefoundry.domainlayer.shipment.application.port.out;

import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;

/**
 * 송장 · 배송 상태 이력 저장. 쓰기는 모두 이 호출에서 flush 한다 — 유일 키 · 버전 충돌이 커밋 시점이 아니라 여기서 드러나 Processor 가 도메인 오류로 바꿀 수 있게.
 */
public interface ShipmentRepositoryPort {

    /** 주문의 송장 — 주문당 1건({@code uk_shipment_order_id}). */
    Optional<Shipment> findByOrderId(long orderId);

    /** 송장 아이디로 (#18 PR-B2). */
    Optional<Shipment> findById(long shipmentId);

    /**
     * 어드민 목록 (#18 PR-B2) — 아이디 내림차순(최신순). 인자가 null 이면 그 조건은 걸지 않는다. 마지막 페이지를 넘긴 요청은 빈 목록이고 전체 건수는 그대로다.
     *
     * @param synced true 면 지금 상태가 order 에 반영된 송장, false 면 반영되지 않은 송장(출고 반영 전 또는 배송 완료 반영 대기)
     * @param page 0 부터 센다
     */
    Page<Shipment> search(Long orderId, ShipmentStatus status, Boolean synced, int page, int size);

    /** 송장의 이력을 시간순(아이디 오름차순)으로. 없으면 빈 목록이다. */
    List<ShipmentStatusHistory> findHistories(long shipmentId);

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
