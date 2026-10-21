package com.commercefoundry.domainlayer.order.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 요청 기록 — 회원 × {@code Idempotency-Key} 하나에 한 행 ({@code order_request}, {@code api-design-guide.md} §6). 주문 아이디는 기록을 처음 넣을 때
 * 미리 발급하고 바뀌지 않는다 — 같은 키의 재시도 · 이어받기는 같은 주문 아이디로 재고를 선점해 product-service 의 업무 키 멱등과 맞물린다.
 *
 * <p>안전성은 주문 아이디(orders PK)와 product 의 활성 선점 유니크에서 나온다. {@code lease} 는 "처리 중 409" 와 중복 원격 호출을 줄이는 장치다 —
 * lease 안의 PROCESSING 은 다른 시도가 처리 중이고, 지나면 이어받는다. 처리한 실패는 lease 를 바로 끝내 같은 키의 재시도가 곧바로 이어받게 한다.
 * 상태 저장은 버전(@Version) 비교라 같은 기록을 두 시도가 동시에 바꾸면 하나만 이긴다.
 *
 * @param requestHash 요청 본문 지문 (SHA-256 hex) — 원문은 남지 않는다. 로그에 남기지 않는다
 * @param leaseExpiresAt 처리 중 lease 가 끝나는 시각. COMPLETED 면 의미 없다
 * @param version 낙관적 락 버전. 저장 전이면 null
 */
public record OrderRequest(
    long id, long memberId, UUID idempotencyKey, String requestHash, long orderId, Status status, Instant leaseExpiresAt, Long version
) {

    public OrderRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }

    /** 처음 잡은 기록 — 처리 중, lease 는 지금부터. */
    public static OrderRequest start(long id, long memberId, UUID idempotencyKey, String requestHash, long orderId, Instant now, Duration lease) {
        return new OrderRequest(id, memberId, idempotencyKey, requestHash, orderId, Status.PROCESSING, now.plus(lease), null);
    }

    /** 같은 본문인가. */
    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }

    /** 다른 시도가 처리 중인가 — PROCESSING 이고 lease 가 아직 남았다. */
    public boolean isLeasedAt(Instant now) {
        return status == Status.PROCESSING && leaseExpiresAt.isAfter(now);
    }

    /**
     * lease 가 끝난 처리 중 기록을 이어받는다 — 주문 아이디는 그대로, lease 만 새로 잡는다.
     *
     * @throws IllegalStateException 완료됐거나 lease 가 남았을 때
     */
    public OrderRequest takeOver(Instant now, Duration lease) {
        if (status != Status.PROCESSING || isLeasedAt(now)) {
            throw new IllegalStateException("이어받을 수 없는 주문 요청 기록입니다: " + status);
        }
        return with(Status.PROCESSING, now.plus(lease));
    }

    /** 처리한 실패 — lease 를 지금 끝낸다. 기록과 주문 아이디는 남겨 같은 키의 재시도가 같은 주문 아이디로 다시 선점하게 한다. */
    public OrderRequest endLease(Instant now) {
        if (status != Status.PROCESSING) {
            throw new IllegalStateException("처리 중이 아닌 주문 요청 기록입니다: " + status);
        }
        return with(Status.PROCESSING, now);
    }

    /**
     * 주문이 저장됐다.
     *
     * @throws IllegalStateException 처리 중이 아닐 때
     */
    public OrderRequest complete() {
        if (status != Status.PROCESSING) {
            throw new IllegalStateException("처리 중이 아닌 주문 요청 기록은 완료할 수 없습니다: " + status);
        }
        return with(Status.COMPLETED, leaseExpiresAt);
    }

    private OrderRequest with(Status newStatus, Instant newLeaseExpiresAt) {
        return new OrderRequest(id, memberId, idempotencyKey, requestHash, orderId, newStatus, newLeaseExpiresAt, version);
    }

    @Override
    public String toString() {
        // 요청 지문은 로그에 남기지 않는다
        return "OrderRequest[id=" + id + ", memberId=" + memberId + ", orderId=" + orderId + ", status=" + status + ", version=" + version + "]";
    }

    public enum Status {

        /** 처리 중 — lease 안이면 다른 시도가 처리 중이다. */
        PROCESSING,
        /** 주문이 저장됐다 — 같은 키 · 같은 본문의 재요청은 그 주문을 다시 읽어 돌려준다. */
        COMPLETED
    }
}
