package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 결제 반영 재고 복원 작업 (#582, {@code orders.order_stock_restoration}). 넣은 뒤에는 읽고 merge 하지 않고 이전 값을 조건으로 건 UPDATE 한 문장으로만 바꾼다 —
 * 0행(false)이면 다른 처리(스윕 인스턴스 · 컨슈머 재전달)가 먼저 바꿨다. 쓰기는 호출 트랜잭션을 필수로 요구한다.
 */
public interface OrderStockRestorationRepositoryPort {

    /** 새 작업을 넣고 바로 flush 한다 — 같은 선점 · 클레임의 행이 이미 있으면 유니크 위반이 이 자리에서 나간다. */
    OrderStockRestoration insert(OrderStockRestoration restoration);

    boolean existsByClaimId(long claimId);

    /** 재전달 시 작업의 전체 출처와 기존 시도·다음 시각을 확인한다. */
    Optional<OrderStockRestoration> findByClaimId(long claimId);

    /** 복원 대기 작업 중 다음 시각이 {@code now} 이하인 복원 대기 행 — 다음 시각 · 아이디 순으로 {@code limit} 개. */
    List<OrderStockRestoration> findDueRestorations(Instant now, int limit);

    Optional<OrderStockRestoration> findByReservationId(long reservationId);

    /** 상태별 행 수 — 적체 경고. */
    long countByStatus(OrderStockRestoration.Status status);

    /** 상태별 가장 오래된 작업의 등록 시각 — 적체 경고. 없으면 빈 값. */
    Optional<Instant> findOldestCreatedAt(OrderStockRestoration.Status status);

    void addConfirmationParticipant(long workId, long paymentId);

    boolean hasResolvedParticipant(long workId, Order order);

    boolean markAbandoned(OrderStockRestoration work, Instant now);

    boolean delete(OrderStockRestoration work);

    /** 스윕 선점 — {@code before} 의 상태 · 다음 시각이 그대로일 때만 {@code claimed}(= {@code before.claimAttempt(...)}) 로 바꾼다. */
    boolean claim(OrderStockRestoration before, OrderStockRestoration claimed, Instant now);

    /** HELD — {@code claimed} 의 상태 · 다음 시각이 그대로일 때만 {@code held}(= {@code claimed.hold(...)}) 로 바꾼다. */
    boolean hold(OrderStockRestoration claimed, OrderStockRestoration held, Instant now);

    /** 복원 완료 — 클레임의 행이 RESTORE_PENDING · HELD 일 때만 RESTORED 로 바꾼다. 행이 없거나 이미 끝났으면 false. */
    boolean markRestored(long claimId, Instant restoredAt);
}
