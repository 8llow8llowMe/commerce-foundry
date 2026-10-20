package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * SKU 재고. 컬렉션 인자 조회는 빈 컬렉션이면 쿼리 없이 빈 목록이다.
 *
 * <p>수량 · 관리 여부를 바꾸는 쓰기는 조건부 UPDATE 한 문장이고 바뀌었는지를 {@code boolean} 으로 돌려준다 — 읽고 고쳐 쓰지 않는다
 * ({@code coding-conventions.md} §9-5). 수정 시각은 어댑터가 현재 시각으로 세팅한다.
 */
public interface StockRepositoryPort {

    /**
     * SKU 아이디 in 절 한 번으로 읽는다. 재고 행이 없는 SKU 는 결과에 없다 — 품절로 본다 ({@code SkuStocks#snapshotOf}).
     */
    List<Stock> findAllBySkuIds(Collection<Long> skuIds);

    /**
     * 단건 {@link #findBySkuId} 와 짝인 묶음 조회 ({@link StockQueryResult} — 수정 시각 포함, 도메인 {@link Stock} 을 내는 {@link #findAllBySkuIds} 와 구분).
     * SKU 아이디 in 절 한 번으로 읽는다 (어드민 재고 조회). 재고 행이 없는 SKU 는 결과에 없다 — 품절로 본다. 순서는 정하지 않는다.
     */
    List<StockQueryResult> findAllWithUpdatedAtBySkuIds(Collection<Long> skuIds);

    /** 재고 행 하나와 수정 시각. 같은 트랜잭션의 조건부 UPDATE 뒤에 부르면 바뀐 값을 읽는다. */
    Optional<StockQueryResult> findBySkuId(long skuId);

    /**
     * 재고 행을 새로 만든다 (INSERT). 이미 행이 있으면 — 두 어드민이 동시에 첫 재고를 등록한 경우 —
     * {@code STOCK_ADJUSTMENT_CONFLICT}(409) 다. 덮어쓰지 않는다.
     */
    void create(Stock stock);

    /**
     * 재고를 관리하는 SKU 의 판매 가능 수량을 {@code quantity} 만큼 줄인다. 수량이 모자라거나 무제한 SKU 이거나 행이 없으면 false 다.
     *
     * @param quantity 1 이상
     */
    boolean deduct(long skuId, long quantity);

    /** 차감했던 수량을 되돌린다. 행이 없으면 false 다. */
    boolean restore(long skuId, long quantity);

    /** 현재 수량이 {@code expectedQuantity} 일 때만 {@code quantity} 로 바꾼다 (CAS). 그 사이 바뀌었거나 행이 없으면 false 다. */
    boolean updateQuantityIfMatches(long skuId, long expectedQuantity, long quantity);

    /** 재고 관리 여부를 바꾼다. 행이 없으면 false 다. */
    boolean updateStockManaged(long skuId, boolean stockManaged);
}
