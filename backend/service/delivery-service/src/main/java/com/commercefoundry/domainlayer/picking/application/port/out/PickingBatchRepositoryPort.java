package com.commercefoundry.domainlayer.picking.application.port.out;

import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import java.util.Optional;

/** 출고 묶음 저장소. 묶음 행(버전) · 담은 주문 · 제외 주문 · 상품 옵션별 수량 · 검수 기록을 한 단위로 읽고 쓴다. */
public interface PickingBatchRepositoryPort {

    /** 묶음과 그 자식(주문 · 제외 · 수량 · 검수)을 묶음 아이디 조건 벌크 조회로 읽는다 — 주문 수와 무관하게 조회 수가 일정하다. */
    Optional<PickingBatch> findById(long batchId);

    /**
     * 새 묶음을 저장한다(persist). 검수 기록이 없고 버전이 null 이어야 한다.
     *
     * @return 저장된 버전을 단 묶음
     */
    PickingBatch insert(PickingBatch batch);

    /**
     * 검수 기록 한 건을 더한다 — 묶음 행을 버전 비교로 먼저 갱신(검수 수 +1)하고, 그다음 마지막 기록을 순번과 함께 넣는다.
     * {@code batch} 는 읽은 묶음에 {@code check} 한 결과여야 한다(기록이 정확히 한 건 늘었다).
     *
     * @return 올라간 버전을 단 묶음
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 검수가 먼저 저장됐다(버전 충돌 · 순번 유일 키 충돌)
     */
    PickingBatch addCheck(PickingBatch batch);
}
