package com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementPaymentMethod;
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
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * PG 정산 거래 — 거래 1건이 한 행이다 (#219). 거래키 유니크 키가 재수집 멱등의 바닥이다 — 같은 거래를 다시 수집하면 이 행의 내용을 바꾼다(upsert).
 *
 * <p>아이디(Snowflake)를 직접 넣고 {@code @Version} 이 없어 {@link Persistable} 로 새 행을 감사 시각으로 판정한다 ({@code coding-conventions.md} §9-1) —
 * 기본 {@code save} 의 merge(행마다 SELECT)를 피하고, 같은 아이디의 두 번째 INSERT 는 PK 위반으로 드러난다. 상태 엔티티가 아니다 — PG 거래의 사본이다.
 */
@Entity
@Table(
    name = "pg_settlement_item",
    comment = "PG 정산 거래 (거래 1건 = 1행, 같은 거래키 재수집은 갱신)",
    uniqueConstraints = @UniqueConstraint(name = "uk_pg_settlement_item_transaction_key", columnNames = "transaction_key"),
    indexes = {
        @Index(name = "idx_pg_settlement_item_payment_key", columnList = "payment_key"),
        @Index(name = "idx_pg_settlement_item_sold_date", columnList = "sold_date")
    },
    check = @CheckConstraint(name = "ck_pg_settlement_item_amount_sign",
        constraint = "(canceled = true and amount <= 0) or (canceled = false and amount >= 0)")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgSettlementItemEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "정산 거래 아이디 (Snowflake)")
    private Long id;

    @Column(name = "transaction_key", nullable = false, length = PgSettlementItem.TRANSACTION_KEY_MAX_LENGTH, comment = "PG 거래키")
    private String transactionKey;

    @Column(name = "payment_key", nullable = false, length = PgSettlementItem.PAYMENT_KEY_MAX_LENGTH,
        comment = "PG 결제키 (결제 기록 payment.payment_key 와 맞춘다 — FK 아님)")
    private String paymentKey;

    @Column(name = "pg_order_id", length = PgSettlementItem.PG_ORDER_ID_MAX_LENGTH, comment = "PG 주문번호 (결제 시도 아이디 문자열). 없으면 null")
    private String pgOrderId;

    @Column(name = "sold_date", nullable = false, comment = "PG 매출일 (KST 날짜)")
    private LocalDate soldDate;

    @Column(name = "paid_out_date", comment = "지급 예정일. 없으면 null")
    private LocalDate paidOutDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 30, comment = "결제수단. 모르는 값은 OTHER, 없으면 null")
    private SettlementPaymentMethod method;

    @Column(name = "amount", nullable = false, comment = "거래 금액 (최소 화폐 단위) — 승인 양수, 취소 음수로 정규화")
    private long amount;

    @Column(name = "fee_amount", nullable = false, comment = "수수료 합계 (fees 공급가 합, 최소 화폐 단위). 저장 · 합계만 한다")
    private long feeAmount;

    @Column(name = "pay_out_amount", nullable = false, comment = "지급 금액 (최소 화폐 단위). 저장 · 합계만 한다")
    private long payOutAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, comment = "거래 · 수수료 · 지급 금액 통화")
    private CurrencyCode currency;

    @Column(name = "canceled", nullable = false, comment = "취소 거래인가")
    private boolean canceled;

    @Column(name = "approved_at", comment = "PG 승인 시각. 없으면 null")
    private Instant approvedAt;

    @Column(name = "collected_at", nullable = false, comment = "마지막으로 수집한 시각")
    private Instant collectedAt;

    public static PgSettlementItemEntity create(long id, PgSettlementItem item, Instant collectedAt) {
        PgSettlementItemEntity entity = new PgSettlementItemEntity();
        entity.id = id;
        entity.overwrite(item, collectedAt);
        return entity;
    }

    /** 같은 거래키를 다시 수집했다 — 내용을 이번 수집 값으로 바꾼다. 거래키는 바꾸지 않는다. */
    public void overwrite(PgSettlementItem item, Instant collectedAt) {
        if (transactionKey != null && !transactionKey.equals(item.transactionKey())) {
            throw new IllegalArgumentException("다른 거래키로 덮어쓸 수 없습니다");
        }
        this.transactionKey = item.transactionKey();
        this.paymentKey = item.paymentKey();
        this.pgOrderId = item.pgOrderId();
        this.soldDate = item.soldDate();
        this.paidOutDate = item.paidOutDate();
        this.method = item.method();
        this.amount = item.amount();
        this.feeAmount = item.feeAmount();
        this.payOutAmount = item.payOutAmount();
        this.currency = item.currency();
        this.canceled = item.canceled();
        this.approvedAt = item.approvedAt();
        this.collectedAt = collectedAt;
    }

    public PgSettlementItem toDomain() {
        return new PgSettlementItem(transactionKey, paymentKey, pgOrderId, soldDate, paidOutDate, method, amount, feeAmount, payOutAmount, currency,
            canceled, approvedAt);
    }

    /** 감사 시각은 persist 때 채워진다 — 비어 있으면 아직 저장하지 않은 행이다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
