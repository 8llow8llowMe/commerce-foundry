package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 결제 후 재고 복원 항목 — 복원 하나 × SKU 하나. 복원을 만들 때 한 번 INSERT 하고 고치지 않는다.
 * 유니크 키 {@code (restoration_id, sku_id)} 가 복원의 항목을 SKU 오름차순으로 읽는 인덱스와 선점별 복원 수량 합의 항목 찾기를 겸한다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code persist}(INSERT) 하게 한다 — {@link StockReservationItemEntity} 와 같은 이유다.
 */
@Entity
@Table(
    name = "stock_restoration_item",
    comment = "결제 후 재고 복원 항목 (복원 × SKU)",
    uniqueConstraints = @UniqueConstraint(name = "uk_stock_restoration_item_restoration_id_sku_id", columnNames = {"restoration_id", "sku_id"}),
    check = @CheckConstraint(name = "ck_stock_restoration_item_quantity_positive", constraint = "quantity > 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockRestorationItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "재고 복원 항목 아이디 (Snowflake)")
    private Long id;

    @Column(name = "restoration_id", nullable = false, comment = "재고 복원 아이디 (FK: stock_restoration.id)")
    private Long restorationId;

    @Column(name = "sku_id", nullable = false, comment = "SKU 아이디 (FK: sku.id)")
    private Long skuId;

    @Column(name = "quantity", nullable = false, comment = "복원 수량 (1 이상). 재고를 되돌리지 않은 무제한 SKU 도 복원 상한 계산에 들어간다")
    private int quantity;

    @Column(name = "restored", nullable = false, comment = "되돌릴 대상(선점 때 차감)이었는가 — 재고 행이 없으면 warn 만 남기고 수량은 늘지 않는다. 무제한 SKU 는 false(기록만)")
    private boolean restored;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
