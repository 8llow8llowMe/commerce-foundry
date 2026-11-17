package com.commercefoundry.domainlayer.picking.application.port.out;

import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import java.util.List;

/** order 의 피킹용 주문 스냅숏 벌크 조회 ({@code GET /internal/v1/orders/picking-snapshots}). 원격 호출이라 DB 트랜잭션 밖에서 부른다. */
public interface OrderPickingSnapshotQueryPort {

    /**
     * 요청한 주문의 스냅숏을 한 번에 읽는다. 없는 주문은 결과에서 빠진다 — 대상 판정은 호출자가 한다. 결과에는 요청한 아이디만 한 번씩 있다.
     *
     * @param orderIds 1~20건 · 양수 · 중복 없음
     * @throws com.commercefoundry.domainlayer.picking.application.exception.PickingException {@code SNAPSHOT_UNAVAILABLE} — 타임아웃 · 5xx · 서킷 열림 · 계약 위반
     */
    List<PickingSnapshot> findAll(List<Long> orderIds);
}
