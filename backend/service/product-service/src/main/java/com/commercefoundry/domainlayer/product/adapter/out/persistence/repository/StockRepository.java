package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * SKU 재고. 아이디(= SKU 아이디) 목록 조회는 {@link #findAllById} 를 쓴다.
 *
 * <p>수량 · 관리 여부를 바꾸는 쓰기는 모두 조건부 UPDATE 한 문장이다 ({@code coding-conventions.md} §9-5) — 읽고 고쳐 쓰면 동시 주문에서 초과 판매가 난다.
 * 반환값은 바뀐 행 수(0 또는 1)다. bulk UPDATE 는 감사 리스너를 거치지 않으므로 {@code updatedAt} 을 인자로 받아 직접 세팅한다.
 * {@code flushAutomatically} 는 같은 트랜잭션에 앞서 쌓인 쓰기를 먼저 내보내고, {@code clearAutomatically} 는 뒤이은 조회가
 * 영속성 컨텍스트의 옛 값이 아니라 DB 의 새 값을 읽게 한다.
 */
public interface StockRepository extends JpaRepository<StockEntity, Long> {

    /**
     * 재고를 관리하는 SKU 의 판매 가능 수량을 줄인다. 수량이 모자라거나, 무제한(재고 관리 안 함) SKU 이거나, 행이 없으면 0행이다.
     * 무제한 SKU 는 수량을 건드리지 않는다 — 호출한 쪽이 차감 대상에서 뺀다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update StockEntity s
           set s.availableQuantity = s.availableQuantity - :quantity, s.updatedAt = :now
         where s.skuId = :skuId and s.stockManaged = true and s.availableQuantity >= :quantity
        """)
    int deduct(long skuId, long quantity, Instant now);

    /** 차감했던 수량을 되돌린다 (선점 해제 · 만료). 관리 여부와 무관하게 더한다. 행이 없으면 0행이다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update StockEntity s
           set s.availableQuantity = s.availableQuantity + :quantity, s.updatedAt = :now
         where s.skuId = :skuId
        """)
    int restore(long skuId, long quantity, Instant now);

    /** 어드민 수량 조정 — 현재 수량이 기대 수량과 같을 때만 바꾼다 (CAS). 그 사이 주문 · 다른 어드민이 바꿨거나 행이 없으면 0행이다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update StockEntity s
           set s.availableQuantity = :quantity, s.updatedAt = :now
         where s.skuId = :skuId and s.availableQuantity = :expectedQuantity
        """)
    int updateQuantityIfMatches(long skuId, long expectedQuantity, long quantity, Instant now);

    /** 재고 관리 여부를 바꾼다. 수량은 그대로 둔다. 행이 없으면 0행이다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update StockEntity s
           set s.stockManaged = :stockManaged, s.updatedAt = :now
         where s.skuId = :skuId
        """)
    int updateStockManaged(long skuId, boolean stockManaged, Instant now);
}
