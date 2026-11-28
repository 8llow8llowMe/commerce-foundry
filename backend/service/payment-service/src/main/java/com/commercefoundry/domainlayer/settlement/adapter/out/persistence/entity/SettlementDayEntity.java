package com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDayStatus;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PG 매출일 하루의 정산 요약 (#219). 매출일 유니크 키로 하루 한 행이다. 상태는 도메인 전이 → 이 엔티티로 매핑 → {@code saveAndFlush}(merge) 로만 바꾼다 —
 * JPQL 로 상태를 바꾸지 않는다. 버전이 {@code Long}(wrapper)이라 새 행은 버전이 null 이고 Spring Data 가 그것으로 새 행을 판정해 persist 한다.
 * 같은 매출일의 수집 · 대사는 이 행을 NOWAIT 로 잠가 줄 세운다.
 */
@Entity
@Table(
    name = "settlement_day",
    comment = "PG 매출일 하루 정산 요약 (수집 · 대사 상태)",
    uniqueConstraints = @UniqueConstraint(name = "uk_settlement_day_sold_date", columnNames = "sold_date"),
    check = {
        @CheckConstraint(name = "ck_settlement_day_counts_non_negative", constraint = "item_count >= 0 and mismatch_count >= 0"),
        @CheckConstraint(name = "ck_settlement_day_status_times",
            constraint = "(status <> 'COLLECTED' or collected_at is not null)"
                + " and (status <> 'RECONCILED' or (collected_at is not null and reconciled_at is not null))")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDayEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "매출일 요약 아이디 (Snowflake)")
    private Long id;

    @Column(name = "sold_date", nullable = false, comment = "PG 매출일 (KST 날짜)")
    private LocalDate soldDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "정산 상태 (COLLECTED / RECONCILED / FAILED)")
    private SettlementDayStatus status;

    @Column(name = "item_count", nullable = false, comment = "마지막 수집의 거래 수 (요약 통화 거래만)")
    private int itemCount;

    @Column(name = "pg_amount_total", nullable = false, comment = "마지막 수집의 거래 금액 합 (최소 화폐 단위, 승인 + 취소 -)")
    private long pgAmountTotal;

    @Column(name = "fee_total", nullable = false, comment = "마지막 수집의 수수료 합 (최소 화폐 단위)")
    private long feeTotal;

    @Column(name = "pay_out_total", nullable = false, comment = "마지막 수집의 지급 금액 합 (최소 화폐 단위)")
    private long payOutTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, comment = "요약 통화 (지금은 KRW 만)")
    private CurrencyCode currency;

    @Column(name = "mismatch_count", nullable = false, comment = "마지막 대사가 본 결제들의 미해결(OPEN) 불일치 수")
    private int mismatchCount;

    @Column(name = "collected_at", comment = "마지막 수집 성공 시각. 한 번도 수집하지 못했으면 null")
    private Instant collectedAt;

    @Column(name = "reconciled_at", comment = "마지막 대사 시각. 한 번도 대사하지 않았으면 null")
    private Instant reconciledAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;

    public static SettlementDayEntity from(SettlementDay day) {
        SettlementDayEntity entity = new SettlementDayEntity();
        entity.id = day.id();
        entity.soldDate = day.soldDate();
        entity.status = day.status();
        entity.itemCount = day.itemCount();
        entity.pgAmountTotal = day.pgAmountTotal();
        entity.feeTotal = day.feeTotal();
        entity.payOutTotal = day.payOutTotal();
        entity.currency = day.currency();
        entity.mismatchCount = day.mismatchCount();
        entity.collectedAt = day.collectedAt();
        entity.reconciledAt = day.reconciledAt();
        entity.version = day.version();
        return entity;
    }

    public SettlementDay toDomain() {
        return new SettlementDay(id, soldDate, status, itemCount, pgAmountTotal, feeTotal, payOutTotal, currency, mismatchCount, collectedAt, reconciledAt,
            version);
    }
}
