package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.shared.commerce.ShippingScope;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 — 금액 · 배송비는 주문 시점 스냅숏이다. 금액은 같은 통화라도 금액마다 통화 컬럼을 짝으로 두고, 같은 통화라는 불변식은 check 제약이 강제한다
 * ({@code coding-conventions.md} §9-3). 배송지(개인정보)는 {@code order_shipping_address} 에 따로 둔다.
 *
 * <p>check 제약은 읽어 올 때 다시 조립하는 주문서({@code OrderQuote} · {@code ShippingFeeQuote})의 불변식 중 이 행 안에서 볼 수 있는 것을 막는다 — 운영 SQL 로
 * 들어간 틀린 행이 같은 키 재생 · 조회에서 500 이 되지 않게, 쓰는 순간 거절한다. 배송 범위 ⇔ 해외 칸(총 무게 · 국가 그룹 · 무게 요금), 해외면 도서산간 없음,
 * 무료배송이면 기준 있음, 배송비 = 산식({@code ShippingFeeQuote#expectedShippingFee}). 줄 금액 합 · 배송 국가처럼 다른 테이블에 걸친 불변식은 막지 못한다.
 * check 식이 NULL 이면 통과하므로 nullable 칸은 {@code is null} · {@code is not null} 로 먼저 가른다.
 *
 * <p>상태는 도메인 메서드로만 바꾸고 {@code @Version} 으로 동시 수정을 막는다 — 버전이 null 이면 새 주문(persist), 아니면 merge(버전 비교 UPDATE).
 * 결제 기한이 지난 결제 대기 주문 스윕의 인덱스({@code status, payment_due_at})는 #96 이 만든다.
 */
@Entity
@Table(
    name = "orders",
    comment = "주문 (금액 · 배송비는 주문 시점 스냅숏, 배송지는 order_shipping_address)",
    uniqueConstraints = @UniqueConstraint(name = "uk_orders_stock_reservation_id", columnNames = "stock_reservation_id"),
    indexes = @Index(name = "idx_orders_member_id_id", columnList = "member_id, id"),
    check = {
        @CheckConstraint(
            name = "ck_orders_amounts_non_negative",
            constraint = "items_amount >= 0 and shipping_fee_amount >= 0 and total_amount >= 0"
                + " and shipping_base_fee_amount >= 0 and shipping_remote_area_surcharge_amount >= 0"
                + " and (shipping_weight_fee_amount is null or shipping_weight_fee_amount >= 0)"
                + " and (shipping_free_shipping_threshold_amount is null or shipping_free_shipping_threshold_amount >= 0)"
        ),
        @CheckConstraint(
            name = "ck_orders_total_amount_consistent",
            constraint = "total_amount = items_amount + shipping_fee_amount"
                + " and shipping_fee_currency = items_currency and total_currency = items_currency"
        ),
        @CheckConstraint(
            name = "ck_orders_shipping_currency_consistent",
            constraint = "shipping_base_fee_currency = items_currency and shipping_remote_area_surcharge_currency = items_currency"
                + " and (shipping_free_shipping_threshold_currency is null or shipping_free_shipping_threshold_currency = items_currency)"
                + " and (shipping_weight_fee_currency is null or shipping_weight_fee_currency = items_currency)"
        ),
        @CheckConstraint(
            name = "ck_orders_shipping_free_shipping_threshold_pair",
            constraint = "(shipping_free_shipping_threshold_amount is null and shipping_free_shipping_threshold_currency is null)"
                + " or (shipping_free_shipping_threshold_amount is not null and shipping_free_shipping_threshold_currency is not null)"
        ),
        @CheckConstraint(
            name = "ck_orders_shipping_weight_fee_pair",
            constraint = "(shipping_weight_fee_amount is null and shipping_weight_fee_currency is null)"
                + " or (shipping_weight_fee_amount is not null and shipping_weight_fee_currency is not null)"
        ),
        @CheckConstraint(name = "ck_orders_shipping_weight_grams_positive", constraint = "shipping_weight_grams is null or shipping_weight_grams >= 1"),
        @CheckConstraint(
            name = "ck_orders_shipping_international_fields",
            constraint = "(shipping_scope = 'DOMESTIC' and shipping_weight_grams is null and shipping_zone_name is null and shipping_weight_fee_amount is null)"
                + " or (shipping_scope = 'INTERNATIONAL' and shipping_weight_grams is not null and shipping_zone_name is not null"
                + " and shipping_weight_fee_amount is not null)"
        ),
        @CheckConstraint(
            name = "ck_orders_shipping_remote_area_domestic_only",
            constraint = "shipping_scope = 'DOMESTIC' or (shipping_remote_area_surcharge_amount = 0 and shipping_remote_area_name is null)"
        ),
        @CheckConstraint(
            name = "ck_orders_shipping_free_shipping_threshold_required",
            constraint = "shipping_free_shipping_applied = false or shipping_free_shipping_threshold_amount is not null"
        ),
        @CheckConstraint(
            name = "ck_orders_shipping_fee_formula",
            constraint = "(shipping_scope = 'DOMESTIC' and shipping_fee_amount"
                + " = (case when shipping_free_shipping_applied then 0 else shipping_base_fee_amount end) + shipping_remote_area_surcharge_amount)"
                + " or (shipping_scope = 'INTERNATIONAL' and shipping_weight_fee_amount is not null and shipping_fee_amount"
                + " = (case when shipping_free_shipping_applied then 0 else shipping_base_fee_amount + shipping_weight_fee_amount end))"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "주문 아이디 (Snowflake — 주문 요청 기록을 잡을 때 미리 발급). 고객에게 보이는 주문 식별자다")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "회원 아이디 (외부 FK: auth-service member.id)")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, comment = "주문 상태 (order-flow-guide.md §1)")
    private OrderStatus status;

    @Column(name = "items_amount", nullable = false, comment = "상품 합계 — Σ(단가 × 수량), 최소 화폐 단위")
    private long itemsAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "items_currency", nullable = false, length = 3, comment = "상품 합계 통화")
    private CurrencyCode itemsCurrency;

    @Column(name = "shipping_fee_amount", nullable = false, comment = "받은 배송비")
    private long shippingFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_fee_currency", nullable = false, length = 3, comment = "배송비 통화")
    private CurrencyCode shippingFeeCurrency;

    @Column(name = "total_amount", nullable = false, comment = "결제 금액 = 상품 합계 + 배송비")
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "total_currency", nullable = false, length = 3, comment = "결제 금액 통화")
    private CurrencyCode totalCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_scope", nullable = false, length = 20, comment = "견적에 쓴 배송비 정책의 배송 범위 (DOMESTIC / INTERNATIONAL)")
    private ShippingScope shippingScope;

    @Column(name = "shipping_base_fee_amount", nullable = false, comment = "견적 시점 정책 기본 배송비 (해외는 기본료)")
    private long shippingBaseFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_base_fee_currency", nullable = false, length = 3, comment = "기본 배송비 통화")
    private CurrencyCode shippingBaseFeeCurrency;

    @Column(name = "shipping_free_shipping_applied", nullable = false, comment = "무료배송 기준 이상이라 기본 배송비(해외는 기본료 + 무게 요금)를 받지 않았는가")
    private boolean shippingFreeShippingApplied;

    @Column(name = "shipping_free_shipping_threshold_amount", comment = "견적 시점 정책의 무료배송 기준. 없으면 null")
    private Long shippingFreeShippingThresholdAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_free_shipping_threshold_currency", length = 3, comment = "무료배송 기준 통화. 기준이 없으면 null")
    private CurrencyCode shippingFreeShippingThresholdCurrency;

    @Column(name = "shipping_remote_area_surcharge_amount", nullable = false, comment = "도서산간 추가 배송비 (아니거나 해외면 0)")
    private long shippingRemoteAreaSurchargeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_remote_area_surcharge_currency", nullable = false, length = 3, comment = "도서산간 추가 배송비 통화")
    private CurrencyCode shippingRemoteAreaSurchargeCurrency;

    @Column(name = "shipping_remote_area_name", length = 50, comment = "도서산간 구간 이름. 아니면 null")
    private String shippingRemoteAreaName;

    @Column(name = "shipping_weight_fee_amount", comment = "해외 무게 요금. 국내면 null")
    private Long shippingWeightFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_weight_fee_currency", length = 3, comment = "해외 무게 요금 통화. 국내면 null")
    private CurrencyCode shippingWeightFeeCurrency;

    @Column(name = "shipping_zone_name", length = 50, comment = "해외 국가 그룹 이름. 국내면 null")
    private String shippingZoneName;

    @Column(name = "shipping_policy_version", nullable = false, comment = "견적에 쓴 배송비 정책 version (외부: delivery-service)")
    private long shippingPolicyVersion;

    @Column(name = "shipping_weight_grams", comment = "견적에 보낸 해외 배송 총 무게(그램, Σ SKU 무게 × 수량). 국내면 null")
    private Integer shippingWeightGrams;

    @Column(name = "stock_reservation_id", nullable = false, comment = "재고 선점 아이디 (외부 FK: product-service stock_reservation.id). 만료 뒤 재선점(#14)하면 바뀐다")
    private Long stockReservationId;

    @Column(name = "payment_due_at", nullable = false, comment = "결제 기한 — 재고 선점 만료 시각")
    private Instant paymentDueAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
