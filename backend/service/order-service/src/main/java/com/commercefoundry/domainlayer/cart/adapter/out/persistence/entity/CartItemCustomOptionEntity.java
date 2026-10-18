package com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
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

/**
 * 줄의 맞춤 제작 입력. 줄의 동일성(해시)이라 줄을 만든 뒤 바뀌지 않는다 — 줄과 함께 넣고 함께 지운다.
 * 유니크 키가 줄 아이디 선두라 {@code cart_item_id in (...)} 벌크 조회 인덱스를 겸한다.
 */
@Entity
@Table(
    name = "cart_item_custom_option",
    comment = "장바구니 줄의 맞춤 제작 입력",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cart_item_custom_option_cart_item_id_field_key", columnNames = {"cart_item_id", "field_key"})
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CartItemCustomOptionEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "맞춤 제작 입력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "cart_item_id", nullable = false, comment = "장바구니 줄 아이디 (FK: cart_item.id)")
    private Long cartItemId;

    @Column(name = "field_key", nullable = false, length = 50, comment = "상품 입력 항목 키 (chest, memo)")
    private String fieldKey;

    @Column(name = "field_value", nullable = false, length = 500, comment = "입력값 (앞뒤 공백을 자른 값)")
    private String fieldValue;

    @Column(name = "display_order", nullable = false, comment = "표시 순서 (담을 때 받은 순서)")
    private int displayOrder;
}
