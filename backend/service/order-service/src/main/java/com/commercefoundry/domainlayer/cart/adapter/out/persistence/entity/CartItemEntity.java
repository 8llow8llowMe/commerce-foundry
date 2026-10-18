package com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가격 · 상품명은 저장하지 않는다 — 조회할 때마다 product-service 의 현재 값을 쓴다. 주문 항목 스냅숏은 주문 생성(#13)이 만든다.
 *
 * <p>유니크 키가 같은 줄의 중복 생성(동시 담기)을 막는다. 회원 아이디가 선두라 회원별 조회 인덱스를 겸한다.
 * 수량 변경은 {@code @Version} 으로 동시 수정을 막는다 — 저장 흐름이 domain → entity → save(merge)라, 버전이 null 이면 새 줄이다.
 */
@Entity
@Table(
    name = "cart_item",
    comment = "장바구니 줄 (회원 × SKU × 맞춤 제작 입력)",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cart_item_member_id_sku_id_custom_option_hash", columnNames = {"member_id", "sku_id", "custom_option_hash"}),
    check = @CheckConstraint(name = "ck_cart_item_quantity_positive", constraint = "quantity >= 1")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CartItemEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "장바구니 줄 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "회원 아이디 (외부 FK: auth-service member.id)")
    private Long memberId;

    @Column(name = "sku_id", nullable = false, comment = "SKU 아이디 (외부 FK: product-service sku.id)")
    private Long skuId;

    @Column(name = "quantity", nullable = false, comment = "수량 (1 이상, 99 · 상품 1회 주문 최대 수량 이하)")
    private int quantity;

    @Column(name = "custom_option_hash", nullable = false, length = 64,
        comment = "맞춤 제작 입력의 정규화 SHA-256 hex. 입력이 없으면 빈 문자열 — 유니크 키에서 null 은 중복을 막지 못한다")
    private String customOptionHash;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
