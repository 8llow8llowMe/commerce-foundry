package com.commercefoundry.domainlayer.order.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 요청 기록 (멱등 — {@code api-design-guide.md} §6). 유니크 키가 같은 회원 · 같은 키를 동시에 잡는 시도 중 하나만 통과시키고, 상태 · lease 갱신은 {@code @Version} 이 막는다.
 * 본문은 지문(SHA-256)만 둔다 — 원문은 남지 않는다. 오래된 기록의 정리 배치는 후속 이슈다.
 */
@Entity
@Table(
    name = "order_request",
    comment = "주문 요청 기록 (회원 × Idempotency-Key)",
    uniqueConstraints = @UniqueConstraint(name = "uk_order_request_member_id_idempotency_key", columnNames = {"member_id", "idempotency_key"})
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderRequestEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "기록 아이디 (Snowflake)")
    private Long id;

    @Column(name = "member_id", nullable = false, comment = "회원 아이디 (외부 FK: auth-service member.id)")
    private Long memberId;

    @Column(name = "idempotency_key", nullable = false, comment = "Idempotency-Key 헤더 (UUID)")
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, comment = "요청 본문 지문 (정규화한 items · 배송지 · expectedTotalAmount 의 SHA-256 hex). 원문은 남지 않는다")
    private String requestHash;

    @Column(name = "order_id", nullable = false, comment = "미리 발급한 주문 아이디 (FK: orders.id — 주문이 저장되기 전에도 있다)")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, comment = "처리 상태 (PROCESSING / COMPLETED)")
    private OrderRequest.Status status;

    @Column(name = "lease_expires_at", nullable = false, comment = "처리 중 lease 가 끝나는 시각 — 지나면 같은 키의 재요청이 이어받는다")
    private Instant leaseExpiresAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
