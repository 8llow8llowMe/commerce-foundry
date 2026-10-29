package com.commercefoundry.domainlayer.skucost.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * SKU 원가 적용 이력. append-only 다 — 행을 고치거나 지우지 않는다. 원가가 바뀌면 새 적용 시작 시각으로 행을 더한다. {@code created_at} 이 기록 시각이다.
 * {@link Immutable} 이라 Hibernate 도 이 행을 UPDATE 하지 않는다 (선례: auth {@code AdminAuditLogEntity}).
 *
 * <p>유니크 {@code (sku_id, cost_type, effective_from)} 가 같은 시작 시각의 두 행을 막고, 유형별 최신 행 · 시점 조회 · 이력 페이지가 이 인덱스를 탄다 — 따로 인덱스를 두지 않는다.
 * 유형 · 통화 check 는 Hibernate 가 enum 칼럼에 만든다. 금액 0 은 "0원이라고 명시했다" 이고 행이 없는 것(미입력)과 다르다.
 *
 * <p>{@link Persistable} 로 새 행이면 {@code merge} 가 아니라 {@code persist}(INSERT) 하게 한다 — 아이디(Snowflake)를 직접 넣는 엔티티라
 * 기본 {@code save} 는 merge 로 가서 SELECT 를 한 번 더 보낸다 ({@code coding-conventions.md} §9-1).
 */
@Entity
@Immutable
@Table(
    name = "sku_cost",
    comment = "SKU 원가 적용 이력 (append-only)",
    uniqueConstraints = @UniqueConstraint(name = "uk_sku_cost_sku_id_cost_type_effective_from", columnNames = {"sku_id", "cost_type", "effective_from"}),
    check = @CheckConstraint(name = "ck_sku_cost_amount_non_negative", constraint = "amount >= 0")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SkuCostEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "SKU 원가 이력 아이디 (Snowflake)")
    private Long id;

    @Column(name = "sku_id", nullable = false, comment = "SKU 아이디 (FK: sku.id)")
    private Long skuId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type", nullable = false, length = 20, comment = "원가 유형 (GOODS 매입 · 제작 원가 / PACKAGING 포장 변동비)")
    private SkuCostType costType;

    @Column(name = "amount", nullable = false, comment = "원가 (0 이상, 최소 화폐 단위). 0 은 0원이라고 명시한 값")
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, comment = "원가 통화 — 같은 SKU · 유형의 첫 행 통화로 고정")
    private CurrencyCode currency;

    @Column(name = "effective_from", nullable = false, comment = "적용 시작 시각 (포함). 같은 SKU · 유형의 다음 행 시작 전까지 적용")
    private Instant effectiveFrom;

    @Column(name = "reason", nullable = false, length = 200, comment = "변경 사유")
    private String reason;

    @Column(name = "admin_member_id", nullable = false, comment = "기록한 어드민 회원 아이디 (외부 FK: auth-service member.id)")
    private Long adminMemberId;

    /** 생성 시각은 최초 INSERT 때 감사 리스너가 채운다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
