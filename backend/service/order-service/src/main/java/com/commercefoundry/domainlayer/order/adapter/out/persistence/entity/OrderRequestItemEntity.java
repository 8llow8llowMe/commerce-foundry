package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

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
 * 주문 요청 기록의 상품 단위 수량 (#436) — 진행 중인 시도의 수량을 상품 수량 상한 재검사에서 센다. 기록과 같은 트랜잭션에서 넣고 바뀌지 않는다.
 * 유니크 키가 기록 아이디 선두라 기록 → 항목 조인의 인덱스를 겸한다.
 *
 * <p>아이디를 직접 넣고 {@code @Version} 이 없어 {@link Persistable} 로 "감사 시각이 없으면 새 행" 이라고 알린다 — 기본 {@code save} 가 merge(SELECT 후 INSERT)로 가지 않게.
 */
@Entity
@Table(
    name = "order_request_item",
    comment = "주문 요청 기록의 상품별 수량 (진행 중 시도의 수량 상한 계수용)",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_request_item_order_request_id_product_id", columnNames = {"order_request_id", "product_id"}),
    check = {
        @CheckConstraint(name = "ck_order_request_item_identifiers_positive", constraint = "id > 0 and order_request_id > 0 and product_id > 0"),
        @CheckConstraint(name = "ck_order_request_item_quantity_positive", constraint = "quantity >= 1")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderRequestItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "항목 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_request_id", nullable = false, comment = "주문 요청 기록 아이디 (FK: order_request.id)")
    private Long orderRequestId;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (외부 FK: product-service product.id)")
    private Long productId;

    @Column(name = "quantity", nullable = false, comment = "요청에 담긴 이 상품의 수량 합 (줄 · SKU 를 합친 상품 단위)")
    private int quantity;

    /** 감사 시각은 persist 때 채워진다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
