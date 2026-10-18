package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 국가 그룹의 무게 구간 — 상한 무게(포함)까지의 요금. 정책 교체마다 모두 지우고 새로 넣는다.
 *
 * <p>유일 키({@code shipping_zone_id}, {@code max_weight_grams})가 그룹별 조회의 인덱스를 겸한다. 요금 통화가 정책 통화와 같은지는
 * 테이블을 넘는 조건이라 DB 로 막지 못한다 — Processor 와 도메인이 막는다.
 */
@Entity
@Table(
    name = "shipping_zone_rate",
    comment = "해외 배송 국가 그룹의 무게 구간 요금 (상한 포함)",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_shipping_zone_rate_shipping_zone_id_max_weight_grams", columnNames = {"shipping_zone_id", "max_weight_grams"}
    ),
    check = {
        @CheckConstraint(name = "ck_shipping_zone_rate_max_weight_grams_positive", constraint = "max_weight_grams > 0"),
        @CheckConstraint(name = "ck_shipping_zone_rate_fee_non_negative", constraint = "fee_amount >= 0")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingZoneRateEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "무게 구간 아이디 (Snowflake). 정책 교체마다 새로 발급")
    private Long id;

    @Column(name = "shipping_zone_id", nullable = false, comment = "국가 그룹 아이디 (FK: shipping_zone.id)")
    private Long shippingZoneId;

    @Column(name = "max_weight_grams", nullable = false, comment = "상한 무게 (그램, 1 이상, 포함). 바로 아래 구간 상한 + 1g 부터 이 구간이다")
    private int maxWeightGrams;

    @Column(name = "fee_amount", nullable = false, comment = "무게 요금 (0 이상, 최소 화폐 단위)")
    private long feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_currency", nullable = false, length = 3, comment = "무게 요금 통화 — 정책 통화와 같아야 한다")
    private CurrencyCode feeCurrency;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    @Builder
    private ShippingZoneRateEntity(Long id, Long shippingZoneId, int maxWeightGrams, long feeAmount, CurrencyCode feeCurrency) {
        this.id = id;
        this.shippingZoneId = shippingZoneId;
        this.maxWeightGrams = maxWeightGrams;
        this.feeAmount = feeAmount;
        this.feeCurrency = feeCurrency;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }
}
