package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.application.info.ExpiredStockReservationInfo;
import java.util.List;

/**
 * 재고 선점 만료 스윕 (스케줄러 진입점). 목록 조회와 건별 만료를 따로 부른다 — 건마다 트랜잭션을 나눠, 한 건의 실패 · 충돌이
 * 같은 배치의 다른 건을 롤백하지 않게 한다.
 */
public interface StockSchedulerUseCase {

    /**
     * 지금 만료 대상인 선점 — 만료 시각 → 아이디 오름차순으로 {@code limit} 건까지.
     *
     * @param after 앞 배치의 마지막 건. 이 건 다음부터 읽는다(keyset) — 앞 배치에서 실패한 건을 다시 읽지 않는다. null 이면 처음부터다
     */
    List<ExpiredStockReservationInfo> findExpiredReservations(ExpiredStockReservationInfo after, int limit);

    /**
     * 선점 한 건을 만료 해제하고 차감했던 재고를 되돌린다. 다시 읽어 아직 RESERVED 이고 만료 시각이 지났을 때만 푼다.
     *
     * @return 풀었으면 true. 그 사이 확정 · 해제됐으면 false
     */
    boolean expire(long reservationId);
}
