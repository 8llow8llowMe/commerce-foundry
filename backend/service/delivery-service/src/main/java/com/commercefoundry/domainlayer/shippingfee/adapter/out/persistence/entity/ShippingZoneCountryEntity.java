package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 해외 배송 국가 그룹에 든 국가. 정책 교체마다 모두 지우고 새로 넣는다.
 *
 * <p>{@code shipping_policy_id} 를 국가 그룹을 거치지 않고 직접 들고 있다 — 유일 키({@code shipping_policy_id}, {@code country_code})로
 * "한 국가는 정책 안에서 한 그룹에만" 을 DB 에서도 막기 위해서다. 정책별 조회 · 삭제도 이 유일 키 인덱스를 탄다.
 *
 * <p>check 제약은 대문자 두 글자와 국내(KR) 제외를 막는다. 알파벳만인지까지는 H2 · PostgreSQL 공통 표현으로 쓰기 어려워 애플리케이션 검증
 * (요청 DTO · 도메인)에 맡긴다.
 */
@Entity
@Table(
    name = "shipping_zone_country",
    comment = "해외 배송 국가 그룹의 국가 (해외 배송비 정책별, 정책 안에서 국가는 한 그룹에만)",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_shipping_zone_country_shipping_policy_id_country_code", columnNames = {"shipping_policy_id", "country_code"}
    ),
    check = {
        @CheckConstraint(
            name = "ck_shipping_zone_country_country_code_format",
            constraint = "char_length(country_code) = 2 and upper(country_code) = country_code"
        ),
        @CheckConstraint(name = "ck_shipping_zone_country_country_code_not_domestic", constraint = "country_code <> 'KR'")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingZoneCountryEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "국가 그룹 국가 아이디 (Snowflake). 정책 교체마다 새로 발급")
    private Long id;

    @Column(name = "shipping_policy_id", nullable = false, comment = "배송비 정책 아이디 (FK: shipping_policy.id)")
    private Long shippingPolicyId;

    @Column(name = "shipping_zone_id", nullable = false, comment = "국가 그룹 아이디 (FK: shipping_zone.id)")
    private Long shippingZoneId;

    @Column(name = "country_code", nullable = false, length = 2, comment = "국가 코드 (ISO 3166-1 alpha-2 대문자, KR 제외)")
    private String countryCode;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    @Builder
    private ShippingZoneCountryEntity(Long id, Long shippingPolicyId, Long shippingZoneId, String countryCode) {
        this.id = id;
        this.shippingPolicyId = shippingPolicyId;
        this.shippingZoneId = shippingZoneId;
        this.countryCode = countryCode;
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
