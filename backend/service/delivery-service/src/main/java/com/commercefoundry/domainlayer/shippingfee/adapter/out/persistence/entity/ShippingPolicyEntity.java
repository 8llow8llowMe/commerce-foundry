package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.ShippingScope;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 범위(국내 · 해외)마다 한 행이다. 국내의 도서산간 구간은 {@link ShippingRemoteAreaEntity}, 해외의 국가 그룹 · 국가 · 무게 구간은
 * {@link ShippingZoneEntity} · {@link ShippingZoneCountryEntity} · {@link ShippingZoneRateEntity} 가 가진다. 관세 · 부가세 안내 문구는 해외 행만 쓴다.
 * 교체는 조건부 UPDATE 한 문장(버전 확인 + 증가)으로 하고 이 엔티티에 변경 메서드를 두지 않는다
 * ({@code ShippingPolicyRepository#updateIfVersionMatches}).
 *
 * <p>check 제약은 금액 · 통화 짝과 음수 · 0 금지를 DB 에서도 막는다 — 매퍼({@code MoneyColumns})와 도메인이 읽을 때 실패하는 데이터가 애초에 들어오지 않게.
 * 도서산간 추가 배송비 통화가 기본 배송비 통화와 같은지는 테이블을 넘는 조건이라 DB 로 막지 못한다 — Processor 와 도메인이 막는다.
 */
@Entity
@Table(
    name = "shipping_policy",
    comment = "배송비 정책 (배송 범위마다 1개)",
    uniqueConstraints = @UniqueConstraint(name = "uk_shipping_policy_scope", columnNames = "scope"),
    check = {
        @CheckConstraint(name = "ck_shipping_policy_base_fee_non_negative", constraint = "base_fee_amount >= 0"),
        @CheckConstraint(
            name = "ck_shipping_policy_free_shipping_threshold_pair",
            constraint = "(free_shipping_threshold_amount is null and free_shipping_threshold_currency is null)"
                + " or (free_shipping_threshold_amount is not null and free_shipping_threshold_currency is not null)"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_free_shipping_threshold_positive",
            constraint = "free_shipping_threshold_amount is null or free_shipping_threshold_amount > 0"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_free_shipping_threshold_currency",
            constraint = "free_shipping_threshold_currency is null or free_shipping_threshold_currency = base_fee_currency"
        ),
        @CheckConstraint(name = "ck_shipping_policy_customs_notice_scope", constraint = "customs_notice is null or scope = 'INTERNATIONAL'")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShippingPolicyEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "배송비 정책 아이디 (Snowflake)")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20, comment = "배송 범위 (DOMESTIC · INTERNATIONAL). 범위마다 정책이 하나다")
    private ShippingScope scope;

    @Column(name = "base_fee_amount", nullable = false, comment = "기본 배송비 (0 이상, 최소 화폐 단위)")
    private long baseFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_fee_currency", nullable = false, length = 3, comment = "정책 통화 — 정책의 모든 금액이 이 통화다")
    private CurrencyCode baseFeeCurrency;

    @Column(name = "free_shipping_threshold_amount",
        comment = "무료배송 기준 주문 금액 (할인 후 상품 합계, 0 초과, 최소 화폐 단위). 이 금액 이상이면 기본 배송비를 받지 않는다. null 이면 무료배송 없음")
    private Long freeShippingThresholdAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "free_shipping_threshold_currency", length = 3, comment = "무료배송 기준 통화 — 기본 배송비 통화와 같다")
    private CurrencyCode freeShippingThresholdCurrency;

    @Column(name = "customs_notice", length = 1000, comment = "관세 · 부가세 고객 부담 안내 문구 (해외 정책만). 없으면 null")
    private String customsNotice;

    /** null 이면 새 엔티티다 — Spring Data 가 {@code persist} 로 저장한다(아이디를 직접 넣어도 {@code merge} 의 선조회가 없다). 저장하면 0 부터 센다. */
    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전. 교체마다 1 증가")
    private Long version;
}
