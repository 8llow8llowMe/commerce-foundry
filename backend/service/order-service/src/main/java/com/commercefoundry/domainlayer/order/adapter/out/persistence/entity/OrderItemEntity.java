package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 주문 항목 — 주문 시점 SKU 스냅숏. 주문과 함께 넣고 바뀌지 않는다. 유니크 키가 주문 아이디 선두라 {@code order_id in (...)} 조회 인덱스를 겸한다.
 *
 * <p>check 제약은 읽어 올 때 다시 조립하는 주문서 줄({@code OrderQuoteLine})의 불변식(수량 범위 · 줄 금액)과 원천 SKU 의 형식(무게 · HS 코드 —
 * product-service {@code ck_sku_weight_grams_positive} · {@code ck_sku_hs_code_format} 과 같은 식)을 막는다.
 *
 * <p>아이디를 직접 넣고 {@code @Version} 이 없어 {@link Persistable} 로 "감사 시각이 없으면 새 행" 이라고 알린다 — 기본 {@code save} 가 merge(SELECT 후 INSERT)로 가지 않게.
 */
@Entity
@Table(
    name = "order_item",
    comment = "주문 항목 (주문 시점 SKU 스냅숏)",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_item_order_id_line_no", columnNames = {"order_id", "line_no"}),
    check = {
        @CheckConstraint(name = "ck_order_item_quantity_range", constraint = "quantity between 1 and " + OrderQuoteLine.MAX_QUANTITY),
        @CheckConstraint(name = "ck_order_item_line_no_positive", constraint = "line_no >= 1"),
        @CheckConstraint(
            name = "ck_order_item_line_amount_consistent",
            constraint = "unit_price_amount >= 0 and line_amount = unit_price_amount * quantity and line_currency = unit_price_currency"
        ),
        @CheckConstraint(name = "ck_order_item_unit_weight_grams_positive", constraint = "unit_weight_grams is null or unit_weight_grams >= 1"),
        @CheckConstraint(
            name = "ck_order_item_hs_code_format",
            constraint = "hs_code is null or (char_length(hs_code) between 6 and 10 and ltrim(hs_code, '0123456789') = '')"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "주문 항목 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (FK: orders.id)")
    private Long orderId;

    @Column(name = "line_no", nullable = false, comment = "요청 items 순서의 1부터 번호")
    private int lineNo;

    @Column(name = "sku_id", nullable = false, comment = "SKU 아이디 (외부 FK: product-service sku.id)")
    private Long skuId;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (외부 FK: product-service product.id)")
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 200, comment = "주문 시점 상품명 (원천 product.name 100자)")
    private String productName;

    @Column(name = "option_name", nullable = false, length = 300,
        comment = "주문 시점 옵션 표시명 — 옵션 값 이름(50자)을 최대 5그룹 \" / \" 로 이은 값이라 최대 262자. 옵션이 없으면 빈 문자열")
    private String optionName;

    @Column(name = "unit_price_amount", nullable = false, comment = "주문 시점 판매가 (옵션 추가금 포함)")
    private long unitPriceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_price_currency", nullable = false, length = 3, comment = "판매가 통화")
    private CurrencyCode unitPriceCurrency;

    @Column(name = "quantity", nullable = false, comment = "수량 (1~99, 줄 단위)")
    private int quantity;

    @Column(name = "custom_order", nullable = false, comment = "주문 시점에 맞춤 제작 상품이었는가")
    private boolean customOrder;

    @Column(name = "line_amount", nullable = false, comment = "판매가 × 수량")
    private long lineAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_currency", nullable = false, length = 3, comment = "줄 금액 통화")
    private CurrencyCode lineCurrency;

    @Column(name = "custom_option_hash", nullable = false, length = 64,
        comment = "맞춤 제작 입력의 정규화 SHA-256 hex (장바구니 줄 해시와 같은 알고리즘). 입력이 없으면 빈 문자열")
    private String customOptionHash;

    @Column(name = "unit_weight_grams", comment = "주문 시점 SKU 무게(그램). 미입력이면 null")
    private Integer unitWeightGrams;

    @Column(name = "hs_code", length = 10, comment = "주문 시점 HS 코드 (숫자 6~10자리). 미입력이면 null")
    private String hsCode;

    /** 감사 시각은 persist 때 채워진다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
