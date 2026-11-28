package com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatch;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchResolution;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchStatus;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType;
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
 * 정산 대사 불일치 (#219). (결제키, 종류) 유니크 키가 재대사 멱등의 바닥이다 — 같은 불일치를 다시 찾으면 행을 늘리지 않고 이 행을 바꾼다.
 * 상태는 도메인 전이 → merge({@code @Version}) 로만 바꾼다. 자바 필드는 {@code mismatchType} 이다 — JPQL 의 {@code TYPE} 예약어와 겹치지 않게.
 */
@Entity
@Table(
    name = "settlement_mismatch",
    comment = "정산 대사 불일치 (결제키 · 종류마다 한 행)",
    uniqueConstraints = @UniqueConstraint(name = "uk_settlement_mismatch_payment_key_type", columnNames = {"payment_key", "type"}),
    check = {
        @CheckConstraint(name = "ck_settlement_mismatch_resolution_status",
            constraint = "(status = 'OPEN' and resolution is null and resolved_at is null)"
                + " or (status = 'RESOLVED' and resolution is not null and resolved_at is not null)"),
        @CheckConstraint(name = "ck_settlement_mismatch_payment_id_type",
            constraint = "(type = 'MISSING_IN_LEDGER' and payment_id is null) or (type <> 'MISSING_IN_LEDGER' and payment_id is not null)")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementMismatchEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "불일치 아이디 (Snowflake)")
    private Long id;

    @Column(name = "sold_date", nullable = false, comment = "처음 발견한 대사의 PG 매출일 (KST 날짜)")
    private LocalDate soldDate;

    @Column(name = "payment_key", nullable = false, length = 200, comment = "PG 결제키")
    private String paymentKey;

    @Column(name = "payment_id", comment = "결제 시도 아이디 (FK: payment.id). 결제 기록 누락(MISSING_IN_LEDGER)이면 null")
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30,
        comment = "불일치 종류 (AMOUNT / STATUS / MISSING_IN_PG / MISSING_IN_LEDGER / CANCEL_TOTAL)")
    private SettlementMismatchType mismatchType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "처리 상태 (OPEN / RESOLVED)")
    private SettlementMismatchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 30, comment = "닫은 방법 (AUTO_CLEARED). OPEN 이면 null")
    private SettlementMismatchResolution resolution;

    @Column(name = "expected_amount", nullable = false, comment = "우리 기록 쪽 금액 (최소 화폐 단위)")
    private long expectedAmount;

    @Column(name = "actual_amount", nullable = false, comment = "PG 쪽 금액 (최소 화폐 단위, 음수일 수 있다)")
    private long actualAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, comment = "금액 통화")
    private CurrencyCode currency;

    @Column(name = "detected_at", nullable = false, comment = "마지막으로 발견한 시각")
    private Instant detectedAt;

    @Column(name = "resolved_at", comment = "닫은 시각. OPEN 이면 null")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;

    public static SettlementMismatchEntity from(SettlementMismatch mismatch) {
        SettlementMismatchEntity entity = new SettlementMismatchEntity();
        entity.id = mismatch.id();
        entity.soldDate = mismatch.soldDate();
        entity.paymentKey = mismatch.paymentKey();
        entity.paymentId = mismatch.paymentId();
        entity.mismatchType = mismatch.type();
        entity.status = mismatch.status();
        entity.resolution = mismatch.resolution();
        entity.expectedAmount = mismatch.expectedAmount();
        entity.actualAmount = mismatch.actualAmount();
        entity.currency = mismatch.currency();
        entity.detectedAt = mismatch.detectedAt();
        entity.resolvedAt = mismatch.resolvedAt();
        entity.version = mismatch.version();
        return entity;
    }

    public SettlementMismatch toDomain() {
        return new SettlementMismatch(id, soldDate, paymentKey, paymentId, mismatchType, status, resolution, expectedAmount, actualAmount, currency,
            detectedAt, resolvedAt, version);
    }
}
