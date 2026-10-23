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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 배송비 정책 교체 이력 (#69). append-only 다 — 행을 고치거나 지우지 않는다. {@code created_at} 이 교체 시각이다.
 * 교체 전 · 후의 금액 · 버전은 이 행이, 도서산간 구간 목록은 {@link ShippingPolicyHistoryAreaEntity} 가 가진다.
 *
 * <p>범위별 최신순({@code where scope = ? order by id desc})으로 읽으므로 {@code (scope, id)} 인덱스를 둔다. 지금은 국내만 쓴다 —
 * 해외 정책 이력을 더할 때 같은 테이블에 다른 범위 행으로 넣는다.
 *
 * <p>check 제약은 정책 테이블과 같은 금액 규칙(짝 · 음수 금지 · 무료배송 기준 통화 = 기본 배송비 통화), "교체 전 값은 통째로 있거나 통째로 없다",
 * "교체 후 버전이 교체 전보다 크다" 를 DB 에서도 막는다 —
 * 매퍼({@code MoneyColumns})가 읽을 때 실패하는 행이 애초에 들어오지 않게.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code persist}(INSERT) 하게 한다 — 아이디(Snowflake)를 직접 넣는 엔티티라 기본 {@code save} 는
 * merge 로 가서 SELECT 를 한 번 더 보내고, 같은 아이디가 겹치면 이력을 조건 없이 덮어쓴다.
 */
@Entity
@Table(
    name = "shipping_policy_history",
    comment = "배송비 정책 교체 이력 (append-only)",
    indexes = @Index(name = "idx_shipping_policy_history_scope_id", columnList = "scope, id"),
    check = {
        @CheckConstraint(
            name = "ck_shipping_policy_history_before_snapshot",
            constraint = "(before_version is null and before_base_fee_amount is null and before_base_fee_currency is null"
                + " and before_free_shipping_threshold_amount is null and before_free_shipping_threshold_currency is null)"
                + " or (before_version is not null and before_base_fee_amount is not null and before_base_fee_currency is not null)"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_history_before_base_fee_non_negative",
            constraint = "before_base_fee_amount is null or before_base_fee_amount >= 0"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_history_before_threshold_pair",
            constraint = "(before_free_shipping_threshold_amount is null and before_free_shipping_threshold_currency is null)"
                + " or (before_free_shipping_threshold_amount is not null and before_free_shipping_threshold_currency is not null)"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_history_before_threshold_positive",
            constraint = "before_free_shipping_threshold_amount is null or before_free_shipping_threshold_amount > 0"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_history_before_threshold_currency",
            constraint = "before_free_shipping_threshold_currency is null or before_free_shipping_threshold_currency = before_base_fee_currency"
        ),
        @CheckConstraint(name = "ck_shipping_policy_history_after_base_fee_non_negative", constraint = "after_base_fee_amount >= 0"),
        @CheckConstraint(
            name = "ck_shipping_policy_history_after_threshold_pair",
            constraint = "(after_free_shipping_threshold_amount is null and after_free_shipping_threshold_currency is null)"
                + " or (after_free_shipping_threshold_amount is not null and after_free_shipping_threshold_currency is not null)"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_history_after_threshold_positive",
            constraint = "after_free_shipping_threshold_amount is null or after_free_shipping_threshold_amount > 0"
        ),
        @CheckConstraint(
            name = "ck_shipping_policy_history_after_threshold_currency",
            constraint = "after_free_shipping_threshold_currency is null or after_free_shipping_threshold_currency = after_base_fee_currency"
        ),
        @CheckConstraint(name = "ck_shipping_policy_history_version_order", constraint = "before_version is null or before_version < after_version")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShippingPolicyHistoryEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "배송비 정책 이력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "shipping_policy_id", nullable = false, comment = "배송비 정책 아이디 (FK: shipping_policy.id)")
    private Long shippingPolicyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20, comment = "배송 범위 (DOMESTIC · INTERNATIONAL). 지금은 DOMESTIC 만 남긴다")
    private ShippingScope scope;

    @Column(name = "admin_member_id", nullable = false, comment = "교체한 어드민 회원 아이디 (외부 FK: auth-service member.id)")
    private Long adminMemberId;

    @Column(name = "before_version", comment = "교체 전 정책 버전. 정책을 처음 만든 교체면 null")
    private Long beforeVersion;

    @Column(name = "before_base_fee_amount", comment = "교체 전 기본 배송비 (0 이상, 최소 화폐 단위). 처음 만든 교체면 null")
    private Long beforeBaseFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_base_fee_currency", length = 3, comment = "교체 전 정책 통화. 처음 만든 교체면 null")
    private CurrencyCode beforeBaseFeeCurrency;

    @Column(name = "before_free_shipping_threshold_amount", comment = "교체 전 무료배송 기준 주문 금액 (0 초과, 최소 화폐 단위). null 이면 무료배송 없음 또는 처음 만든 교체")
    private Long beforeFreeShippingThresholdAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_free_shipping_threshold_currency", length = 3, comment = "교체 전 무료배송 기준 통화")
    private CurrencyCode beforeFreeShippingThresholdCurrency;

    @Column(name = "after_version", nullable = false, comment = "교체 후 정책 버전")
    private long afterVersion;

    @Column(name = "after_base_fee_amount", nullable = false, comment = "교체 후 기본 배송비 (0 이상, 최소 화폐 단위)")
    private long afterBaseFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_base_fee_currency", nullable = false, length = 3, comment = "교체 후 정책 통화")
    private CurrencyCode afterBaseFeeCurrency;

    @Column(name = "after_free_shipping_threshold_amount", comment = "교체 후 무료배송 기준 주문 금액 (0 초과, 최소 화폐 단위). null 이면 무료배송 없음")
    private Long afterFreeShippingThresholdAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_free_shipping_threshold_currency", length = 3, comment = "교체 후 무료배송 기준 통화")
    private CurrencyCode afterFreeShippingThresholdCurrency;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
