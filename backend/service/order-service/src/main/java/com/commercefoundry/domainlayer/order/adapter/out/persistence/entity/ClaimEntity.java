package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "claim", comment = "주문 클레임 접수",
    uniqueConstraints = @UniqueConstraint(name = "uk_claim_order_id_request_key", columnNames = {"order_id", "request_key"}),
    check = {
        @CheckConstraint(name = "ck_claim_identifiers_positive", constraint = "id > 0 and order_id > 0 and version >= 0"),
        @CheckConstraint(name = "ck_claim_expected_refund_pair", constraint = "(expected_refund_amount is null and expected_refund_currency is null) or "
            + "(expected_refund_amount is not null and expected_refund_currency is not null and expected_refund_amount >= 0)")
    })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ClaimEntity extends BaseEntity {
    @Id
    @Column(name = "id", comment = "클레임 아이디 (Snowflake)")
    private Long id;

    @Column(name = "order_id", nullable = false, comment = "주문 아이디 (FK: orders.id)")
    private Long orderId;

    @Column(name = "request_key", nullable = false, comment = "주문별 멱등 접수 키 (UUID)")
    private UUID requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, comment = "클레임 유형 (RETURN)")
    private Claim.Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "클레임 상태 (REQUESTED)")
    private Claim.Status status;

    @Column(name = "expected_refund_amount", comment = "예상 환불 최소 단위 금액 (미산정이면 null)")
    private Long expectedRefundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_refund_currency", length = 3, comment = "예상 환불 통화 (미산정이면 null)")
    private CurrencyCode expectedRefundCurrency;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
