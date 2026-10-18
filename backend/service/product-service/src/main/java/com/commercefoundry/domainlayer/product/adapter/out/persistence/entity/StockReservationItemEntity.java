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
 * 재고 선점 항목 — 선점 하나 × SKU 하나. 선점을 만들 때 한 번 INSERT 하고 고치지 않는다.
 * 유니크 키 {@code (reservation_id, sku_id)} 가 선점의 항목을 SKU 오름차순으로 읽는 인덱스를 겸한다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code merge} 가 아니라 {@code persist}(INSERT) 하게 한다. 아이디(Snowflake)를 직접 넣는 엔티티라
 * 기본 {@code save} 는 merge 로 가서 행마다 SELECT 를 한 번 더 보낸다.
 */
@Entity
@Table(
    name = "stock_reservation_item",
    comment = "재고 선점 항목 (선점 × SKU)",
    uniqueConstraints = @UniqueConstraint(name = "uk_stock_reservation_item_reservation_id_sku_id", columnNames = {"reservation_id", "sku_id"}),
    check = @CheckConstraint(name = "ck_stock_reservation_item_quantity_positive", constraint = "quantity > 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockReservationItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "재고 선점 항목 아이디 (Snowflake)")
    private Long id;

    @Column(name = "reservation_id", nullable = false, comment = "재고 선점 아이디 (FK: stock_reservation.id)")
    private Long reservationId;

    @Column(name = "sku_id", nullable = false, comment = "SKU 아이디 (FK: sku.id)")
    private Long skuId;

    @Column(name = "quantity", nullable = false, comment = "선점 수량 (1 이상)")
    private int quantity;

    @Column(name = "deducted", nullable = false, comment = "선점 때 재고를 차감했는가. 무제한 SKU 는 false 이고 해제 · 만료 때 되돌리지 않는다")
    private boolean deducted;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
