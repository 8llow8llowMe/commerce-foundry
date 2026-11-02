package com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentEntity;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import org.springframework.data.domain.Page;

/**
 * 어드민 송장 목록 (#18 PR-B2). 주문 · 상태 · 반영 여부가 각각 켜지고 꺼지므로 파생 쿼리 조합 대신 QueryDSL 이다 ({@code coding-conventions.md} §9-5).
 */
public interface ShipmentCustomRepository {

    /**
     * 아이디 내림차순(최신순). 인자가 null 이면 그 조건은 걸지 않는다. 건수는 같은 조건이다.
     *
     * @param orderId 주문 — 주문당 1건이라 0~1건이다({@code uk_shipment_order_id})
     * @param synced true 면 지금 상태가 order 에 반영된 송장({@code order_synced and not completion_pending}), false 면 반영되지 않은 송장
     *     (출고 반영 전 또는 배송 완료 반영 대기)
     * @param page 0 부터 센다
     */
    Page<ShipmentEntity> search(Long orderId, ShipmentStatus status, Boolean synced, int page, int size);
}
