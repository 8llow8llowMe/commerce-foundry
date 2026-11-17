package com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity;

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
 * 대상 주문의 상품 옵션별 꺼낼 수량 — 같은 옵션 여러 줄은 주문 안에서 합쳐 한 행이다({@code uk_picking_item_batch_id_order_id_sku_id}). 유일 키가 {@code batch_id} 로
 * 시작해 묶음별 조회 인덱스를 겸한다. 묶음 전체 합계는 저장하지 않고 읽을 때 더한다. append-only 라 {@link Persistable} 로 persist 한다.
 */
@Entity
@Table(
    name = "picking_item",
    comment = "출고 묶음 대상 주문의 상품 옵션별 수량",
    uniqueConstraints = @UniqueConstraint(name = "uk_picking_item_batch_id_order_id_sku_id", columnNames = {"batch_id", "order_id", "sku_id"}),
    check = @CheckConstraint(name = "ck_picking_item_quantity", constraint = "quantity > 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PickingItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "출고 묶음 수량 아이디 (Snowflake)")
    private Long id;

    @Column(name = "batch_id", nullable = false, comment = "출고 묶음 아이디 (FK: picking_batch.id)")
    private Long batchId;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (외부 FK: order-service orders.id)")
    private Long orderId;

    @Column(name = "sku_id", nullable = false, comment = "상품 옵션 아이디 (외부 FK: product-service sku.id)")
    private Long skuId;

    @Column(name = "quantity", nullable = false, comment = "이 주문에서 꺼낼 수량 — 같은 상품 옵션 줄을 합친 값")
    private long quantity;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
