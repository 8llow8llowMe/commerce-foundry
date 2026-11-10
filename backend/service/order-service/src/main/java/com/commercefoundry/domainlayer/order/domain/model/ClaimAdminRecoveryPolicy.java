package com.commercefoundry.domainlayer.order.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** 관리자 수습의 신규 실행 자격만 확인하는 순수 정책. 멱등 성공 판정과 상태 전이 · 호출부는 후속 범위다. */
public final class ClaimAdminRecoveryPolicy {
    private static final String ORDER_STATUS_UNEXPECTED = "ORDER_STATUS_UNEXPECTED";
    private static final Set<String> COMPLETABLE_FAILURES = Set.of("PRODUCT_027", "PRODUCT_028", "PRODUCT_029", "PRODUCT_003",
        "RESTORATION_DATA_INVALID", ORDER_STATUS_UNEXPECTED);

    private ClaimAdminRecoveryPolicy() {
    }

    public static void requireRetry(Claim claim, Instant now) {
        requireStoredCancellation(claim);
        Objects.requireNonNull(now, "now");
        if ((!claim.status().isAwaitingAttempt() && !claim.status().isFailed()) || ORDER_STATUS_UNEXPECTED.equals(claim.failureCode())
            || (claim.nextAttemptAt() != null && claim.nextAttemptAt().isAfter(now))) {
            throw new IllegalStateException("활성 선점 · 종결 완료 · 주문 상태 이상 클레임은 재시도하지 않습니다");
        }
    }

    public static void requireRefundConfirmation(Claim claim) {
        requireStoredCancellation(claim);
        if (claim.status() != Claim.Status.REFUND_FAILED) {
            throw new IllegalStateException("종결된 환불 실패만 수동 확정 대상으로 확인합니다");
        }
    }

    public static void requireCompletion(Claim claim, Order order) {
        requireStoredCancellation(claim);
        Objects.requireNonNull(order, "order");
        if (order.isNew() || order.id() != claim.orderId() || order.status() != OrderStatus.CANCELED) {
            throw new IllegalStateException("같은 주문의 취소 완료를 확인해야 종결합니다");
        }
        if (claim.status() != Claim.Status.RESTORATION_FAILED || !COMPLETABLE_FAILURES.contains(claim.failureCode())) {
            throw new IllegalStateException("확정된 복원 종결 실패만 수동 종결합니다");
        }
    }

    private static void requireStoredCancellation(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.isNew() || claim.type() != Claim.Type.CANCELLATION) {
            throw new IllegalStateException("저장된 취소 클레임만 수습합니다");
        }
    }
}
