package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.ExpiredStockReservationQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 재고 선점 (헤더 + 항목). 상태는 도메인 전이({@link StockReservation#confirm} · {@link StockReservation#release}) 뒤 {@link #update} 로 저장한다 —
 * JPQL 로 상태를 바꾸지 않는다. 헤더의 {@code @Version} 이 동시 변경을 하나만 통과시킨다.
 */
public interface StockReservationRepositoryPort {

    /** 헤더와 항목(SKU 아이디 오름차순)을 함께 읽는다. 쿼리 2번이다. */
    Optional<StockReservation> findById(long reservationId);

    /** 주문의 활성(RESERVED · CONFIRMED) 선점. 주문당 하나다 — {@code uk_stock_reservation_active_order_id}. */
    Optional<StockReservation> findActiveByOrderId(long orderId);

    /**
     * 만료 대상(RESERVED 이고 만료 시각이 {@code now} 이전 · 같음). 만료 시각 → 아이디 오름차순으로 {@code limit} 건까지다.
     *
     * @param after keyset 커서 — 이 건의 {@code (expiresAt, reservationId)} 다음부터 읽는다. null 이면 처음부터다
     */
    List<ExpiredStockReservationQueryResult> findExpiredReservations(Instant now, ExpiredStockReservationQueryResult after, int limit);

    /**
     * SKU 하나의 RESERVED 선점 중 재고를 실제로 차감한({@code deducted}) 항목의 수량 합. 없으면 0 이다. 쿼리 1번이다.
     * 어드민 재고 화면이 "판매 가능 수량 + 선점 수량 = 실물" 을 보여 주는 데 쓴다 — CONFIRMED 는 이미 팔린 수량이라 넣지 않는다.
     */
    long sumReservedQuantityBySkuId(long skuId);

    /**
     * 새 선점(RESERVED)의 헤더만 INSERT 하고 바로 flush 한다. 같은 주문의 활성 선점이 그 사이 생겼으면(동시 생성)
     * {@code STOCK_RESERVATION_CONFLICT}(409) 다. 항목은 재고 차감 결과(deducted)가 정해진 뒤 {@link #createItems} 로 쓴다.
     */
    void createHeader(long reservationId, long orderId, Instant expiresAt);

    /** {@link #createHeader} 로 만든 선점의 항목을 INSERT 하고 바로 flush 한다. 헤더는 쓰지 않는다. */
    void createItems(StockReservation reservation);

    /**
     * 헤더 상태를 저장하고 바로 flush 한다 (버전 검사). 읽은 뒤 다른 트랜잭션이 바꿨으면 {@code STOCK_RESERVATION_CONFLICT}(409) 다.
     * 항목은 바뀌지 않으므로 쓰지 않는다.
     */
    void update(StockReservation reservation);
}
