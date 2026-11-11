package com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 클레임당 한 취소. claim_id가 PG 멱등 키이기도 하다. */
@Entity
@Table(name = "payment_cancel", indexes = @Index(name = "idx_payment_cancel_payment_id", columnList = "payment_id"),
    check = @CheckConstraint(name = "ck_payment_cancel_amount_positive", constraint = "amount > 0"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancelEntity extends BaseEntity {

    @Id
    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentCancel.Status status;

    @Version
    private Long version;

    public static PaymentCancelEntity from(PaymentCancel cancel) {
        PaymentCancelEntity entity = new PaymentCancelEntity();
        entity.claimId = cancel.claimId();
        entity.paymentId = cancel.paymentId();
        entity.amount = cancel.amount().amount();
        entity.currency = cancel.amount().currency();
        entity.status = cancel.status();
        return entity;
    }

    public PaymentCancel toDomain() {
        return new PaymentCancel(claimId, paymentId, Money.of(amount, currency), status, getCreatedAt(), getUpdatedAt());
    }

    public void setStatus(PaymentCancel.Status status) {
        this.status = status;
    }
}
