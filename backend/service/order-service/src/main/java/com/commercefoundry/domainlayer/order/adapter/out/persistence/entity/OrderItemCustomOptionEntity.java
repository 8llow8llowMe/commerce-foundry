package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 주문 항목의 맞춤 제작 입력 — 주문과 함께 넣고 바뀌지 않는다. 주문 단위 조회({@code order_id} · 항목 순)는 {@code idx_order_item_custom_option_order_id_order_item_id} 가 받는다.
 */
@Entity
@Table(
    name = "order_item_custom_option",
    comment = "주문 항목의 맞춤 제작 입력 (주문 시점 스냅숏)",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_order_item_custom_option_order_item_id_field_key", columnNames = {"order_item_id", "field_key"}),
    indexes = @Index(name = "idx_order_item_custom_option_order_id_order_item_id", columnList = "order_id, order_item_id")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderItemCustomOptionEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "맞춤 제작 입력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (FK: orders.id) — 주문 단위로 읽고 지우는 일이 항목을 거치지 않게")
    private Long orderId;

    @Column(name = "order_item_id", nullable = false, comment = "주문 항목 아이디 (FK: order_item.id)")
    private Long orderItemId;

    @Column(name = "field_key", nullable = false, length = 50, comment = "상품 입력 항목 키 (chest, memo)")
    private String fieldKey;

    @Column(name = "field_value", nullable = false, length = 500, comment = "입력값 — 앞뒤 공백을 자르고 NFC 정규화한 원문. 서버가 HTML 을 거르지 않는다(화면 · 메일 · CSV 는 텍스트로 이스케이프)")
    private String fieldValue;

    @Column(name = "display_order", nullable = false, comment = "표시 순서 (받은 순서)")
    private int displayOrder;

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
