package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 도서산간 우편번호 구간. 정책 교체마다 모두 지우고 새로 넣는다 — 구간 아이디도 새로 발급된다.
 *
 * <p>아이디를 직접 넣는(Snowflake) 엔티티라 {@link Persistable} 로 "새 엔티티" 를 알린다. 그러지 않으면 {@code saveAll} 이 구간마다
 * {@code merge} 로 선조회 SELECT 를 보낸다(구간 200개면 SELECT 200번).
 */
@Entity
@Table(
    name = "shipping_remote_area",
    comment = "도서산간 우편번호 구간 (배송비 정책별)",
    indexes = @Index(name = "idx_shipping_remote_area_shipping_policy_id", columnList = "shipping_policy_id"),
    check = {
        // 형식이 틀린 값(4자리 · 문자)이 들어가면 읽을 때 PostalCode 생성이 실패해 정책 조회 · 견적이 500 이 된다 (#69). 정규식은 H2 · PostgreSQL 문법이 달라
        // ltrim 으로 숫자를 걷어낸 나머지가 비었는지 본다 (coding-conventions.md §9-4)
        @CheckConstraint(
            name = "ck_shipping_remote_area_postal_code_format",
            constraint = "char_length(postal_code_from) = 5 and ltrim(postal_code_from, '0123456789') = ''"
                + " and char_length(postal_code_to) = 5 and ltrim(postal_code_to, '0123456789') = ''"
        ),
        @CheckConstraint(name = "ck_shipping_remote_area_postal_code_range", constraint = "postal_code_from <= postal_code_to"),
        @CheckConstraint(name = "ck_shipping_remote_area_surcharge_non_negative", constraint = "surcharge_amount >= 0")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingRemoteAreaEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "도서산간 구간 아이디 (Snowflake). 정책 교체마다 새로 발급")
    private Long id;

    @Column(name = "shipping_policy_id", nullable = false, comment = "배송비 정책 아이디 (FK: shipping_policy.id)")
    private Long shippingPolicyId;

    @Column(name = "name", nullable = false, length = 50, comment = "구간 이름 (예: 제주)")
    private String name;

    @Column(name = "postal_code_from", nullable = false, length = 5, comment = "시작 우편번호 (포함, 숫자 5자리)")
    private String postalCodeFrom;

    @Column(name = "postal_code_to", nullable = false, length = 5, comment = "끝 우편번호 (포함, 숫자 5자리)")
    private String postalCodeTo;

    @Column(name = "surcharge_amount", nullable = false, comment = "추가 배송비 (0 이상, 최소 화폐 단위). 무료배송이어도 받는다")
    private long surchargeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "surcharge_currency", nullable = false, length = 3, comment = "추가 배송비 통화 — 정책 통화와 같아야 한다")
    private CurrencyCode surchargeCurrency;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    @Builder
    private ShippingRemoteAreaEntity(
        Long id, Long shippingPolicyId, String name, String postalCodeFrom, String postalCodeTo,
        long surchargeAmount, CurrencyCode surchargeCurrency
    ) {
        this.id = id;
        this.shippingPolicyId = shippingPolicyId;
        this.name = name;
        this.postalCodeFrom = postalCodeFrom;
        this.postalCodeTo = postalCodeTo;
        this.surchargeAmount = surchargeAmount;
        this.surchargeCurrency = surchargeCurrency;
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
