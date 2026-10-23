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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 배송비 정책 이력의 도서산간 구간 스냅숏 (#69) — 이력 한 건의 교체 전({@code BEFORE}) · 후({@code AFTER}) 구간 목록을 행으로 펼친다. append-only 다.
 *
 * <p>JSON 한 칸이 아니라 행으로 두는 이유 — 금액 · 통화 짝과 우편번호 형식을 정책 테이블과 같은 check 제약으로 막고, 스냅숏 테스트 · {@code validate} 가
 * 모양을 대조하게 하려고다. 아이디는 이력 행마다 새로 발급한다(정책 구간의 아이디는 교체마다 바뀌어 이력에서 뜻이 없다).
 *
 * <p>{@link Persistable} 인 이유는 {@link ShippingPolicyHistoryEntity} 와 같다 — 구간 200개면 merge 선조회 SELECT 200번을 막는다.
 */
@Entity
@Table(
    name = "shipping_policy_history_area",
    comment = "배송비 정책 이력의 도서산간 구간 스냅숏 (append-only)",
    indexes = @Index(name = "idx_shipping_policy_history_area_shipping_policy_history_id", columnList = "shipping_policy_history_id"),
    check = {
        @CheckConstraint(
            name = "ck_shipping_policy_history_area_postal_code_format",
            constraint = "char_length(postal_code_from) = 5 and ltrim(postal_code_from, '0123456789') = ''"
                + " and char_length(postal_code_to) = 5 and ltrim(postal_code_to, '0123456789') = ''"
        ),
        @CheckConstraint(name = "ck_shipping_policy_history_area_postal_code_range", constraint = "postal_code_from <= postal_code_to"),
        @CheckConstraint(name = "ck_shipping_policy_history_area_surcharge_non_negative", constraint = "surcharge_amount >= 0")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShippingPolicyHistoryAreaEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "이력 구간 아이디 (Snowflake)")
    private Long id;

    @Column(name = "shipping_policy_history_id", nullable = false, comment = "배송비 정책 이력 아이디 (FK: shipping_policy_history.id)")
    private Long shippingPolicyHistoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_side", nullable = false, length = 10, comment = "어느 쪽 구간인가 (BEFORE 교체 전 · AFTER 교체 후)")
    private Side snapshotSide;

    @Column(name = "name", nullable = false, length = 50, comment = "구간 이름 (예: 제주)")
    private String name;

    @Column(name = "postal_code_from", nullable = false, length = 5, comment = "시작 우편번호 (포함, 숫자 5자리)")
    private String postalCodeFrom;

    @Column(name = "postal_code_to", nullable = false, length = 5, comment = "끝 우편번호 (포함, 숫자 5자리)")
    private String postalCodeTo;

    @Column(name = "surcharge_amount", nullable = false, comment = "추가 배송비 (0 이상, 최소 화폐 단위)")
    private long surchargeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "surcharge_currency", nullable = false, length = 3, comment = "추가 배송비 통화")
    private CurrencyCode surchargeCurrency;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }

    /** 이력의 교체 전 · 후 중 어느 쪽 구간인가. 저장 값이라 이름을 바꾸지 않는다. */
    public enum Side {
        BEFORE,
        AFTER
    }
}
