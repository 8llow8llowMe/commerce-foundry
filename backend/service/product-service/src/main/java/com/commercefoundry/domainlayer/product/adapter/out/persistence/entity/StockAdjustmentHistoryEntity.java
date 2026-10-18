package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 어드민 재고 조정 이력. append-only 다 — 행을 고치거나 지우지 않는다. {@code created_at} 이 조정 시각이다.
 * SKU 별 최근 이력을 아이디(Snowflake — 시각 순) 내림차순으로 읽으므로 {@code (sku_id, id)} 인덱스를 둔다.
 */
@Entity
@Table(
    name = "stock_adjustment_history",
    comment = "어드민 재고 조정 이력 (append-only)",
    indexes = @Index(name = "idx_stock_adjustment_history_sku_id_id", columnList = "sku_id, id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockAdjustmentHistoryEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "재고 조정 이력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "sku_id", nullable = false, comment = "SKU 아이디 (FK: sku.id)")
    private Long skuId;

    @Column(name = "before_quantity", nullable = false, comment = "조정 전 판매 가능 수량. 재고 행이 없던 SKU 는 0 (품절)")
    private long beforeQuantity;

    @Column(name = "after_quantity", nullable = false, comment = "조정 후 판매 가능 수량")
    private long afterQuantity;

    @Column(name = "delta", nullable = false, comment = "수량 변화 (after_quantity - before_quantity)")
    private long delta;

    @Column(name = "before_stock_managed", nullable = false, comment = "조정 전 재고 관리 여부. 재고 행이 없던 SKU 는 true (품절)")
    private boolean beforeStockManaged;

    @Column(name = "after_stock_managed", nullable = false, comment = "조정 후 재고 관리 여부")
    private boolean afterStockManaged;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30, comment = "조정 사유 (RESTOCK / RECOUNT / DAMAGE / MANAGEMENT_CHANGE / OTHER)")
    private StockAdjustmentReason reason;

    @Column(name = "memo", length = 200, comment = "조정 메모")
    private String memo;

    @Column(name = "admin_member_id", nullable = false, comment = "조정한 어드민 회원 아이디 (외부 FK: auth-service 회원 id)")
    private Long adminMemberId;
}
