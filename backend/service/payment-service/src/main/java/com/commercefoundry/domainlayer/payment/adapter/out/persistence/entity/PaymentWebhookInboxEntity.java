package com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.Reason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentWebhookInbox.State;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_webhook_inbox", comment = "검증된 로컬 결제 바인딩의 웹훅 조회 작업. 원문 미보관",
    uniqueConstraints = @UniqueConstraint(name = "uk_payment_webhook_inbox_fingerprint", columnNames = "fingerprint"),
    indexes = @Index(name = "idx_payment_webhook_inbox_state_available_at_id", columnList = "state,available_at,id"),
    check = {
        @CheckConstraint(name = "ck_payment_webhook_inbox_attempts", constraint = "attempts >= 0"),
        @CheckConstraint(name = "ck_payment_webhook_inbox_claim",
            constraint = "(state = 'PROCESSING' and claim_token is not null) or (state <> 'PROCESSING' and claim_token is null)")
    })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentWebhookInboxEntity {
    @Id @Column(comment = "수신 작업 아이디 (Snowflake)") private Long id;
    @Column(nullable = false, comment = "로컬 결제 아이디 (FK: payment.id)") private long paymentId;
    @Column(nullable = false, length = 64, comment = "정규화된 이벤트 SHA-256. 로그 출력 금지") private String fingerprint;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private State state;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false, comment = "다음 시도 시각. 처리 중이면 lease 만료 시각") private Instant availableAt;
    @Column(length = 36) private String claimToken;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Reason reason;
    @Column(nullable = false) private Instant receivedAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private Long version;

    public static PaymentWebhookInboxEntity from(PaymentWebhookInbox row) {
        return PaymentWebhookInboxEntity.builder().id(row.id()).paymentId(row.paymentId()).fingerprint(row.fingerprint()).state(row.state())
            .attempts(row.attempts()).availableAt(row.availableAt()).claimToken(row.claimToken()).reason(row.reason())
            .receivedAt(row.receivedAt()).updatedAt(row.updatedAt()).version(row.version()).build();
    }
    public PaymentWebhookInbox toDomain() {
        return new PaymentWebhookInbox(id, paymentId, fingerprint, state, attempts, availableAt, claimToken, reason, receivedAt, updatedAt, version);
    }
}
