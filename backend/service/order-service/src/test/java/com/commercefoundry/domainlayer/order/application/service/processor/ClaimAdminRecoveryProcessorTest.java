package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 운영자 다시 시도의 선점 (#17 A2b) — 화면 버전 대조 · 자격 · lease · 저장 경합. 테스트 값은 모두 가짜다. */
class ClaimAdminRecoveryProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final List<ClaimItem> ITEMS = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));

    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static OrderCancellationProperties properties(boolean enabled) {
        return new OrderCancellationProperties(false, new OrderCancellationProperties.Retry(enabled, Duration.ofMinutes(1), 50, 48, Duration.ofMinutes(1),
            Duration.ofHours(1), LEASE));
    }

    private ClaimAdminRecoveryProcessor processor(boolean enabled) {
        return new ClaimAdminRecoveryProcessor(claims, properties(enabled), clock);
    }

    private static Claim cancellation(Claim.Status status, Instant next, String failure) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, 5, next, failure, ITEMS, NOW, 4L);
    }

    private Claim stored(Claim claim) {
        when(claims.findById(2L)).thenReturn(Optional.of(claim));
        when(claims.update(any())).thenAnswer(invocation -> {
            Claim saved = invocation.getArgument(0);
            return new Claim(saved.id(), saved.orderId(), saved.requestKey(), saved.type(), saved.status(), saved.expectedRefund(), saved.paymentId(),
                saved.attemptCount(), saved.nextAttemptAt(), saved.failureCode(), saved.decision(), saved.items(), saved.requestedAt(), saved.version() + 1);
        });
        return claim;
    }

    private static void assertCode(Runnable call, OrderErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(OrderException.class)
            .satisfies(exception -> assertThat(((OrderException) exception).getErrorCode()).isEqualTo(code));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"REFUND_FAILED, REFUND_UNCONFIRMED, REQUESTED", "REFUND_FAILED, PAYMENT_016, REQUESTED",
        "RESTORATION_FAILED, RESTORATION_UNCONFIRMED, REFUNDED", "RESTORATION_FAILED, PRODUCT_028, REFUNDED"})
    @DisplayName("종결 실패는 그 단계의 대기 상태로 다시 열어 lease 까지 선점하고 저장된 값(올라간 버전)을 돌려준다")
    void reopensFailedClaim(Claim.Status status, String failure, Claim.Status reopened) {
        stored(cancellation(status, null, failure));

        Claim held = processor(true).prepareRetry(2, 4);

        assertThat(held.status()).isEqualTo(reopened);
        assertThat(held.attemptCount()).isZero();
        assertThat(held.failureCode()).isNull();
        assertThat(held.nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(held.version()).isEqualTo(5L);
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED"})
    @DisplayName("lease 가 끝난 대기 상태는 상태 · 횟수를 그대로 두고 다음 시각만 lease 로 미뤄 선점한다")
    void holdsDueAwaitingClaim(Claim.Status status) {
        stored(cancellation(status, NOW, null));

        Claim held = processor(true).prepareRetry(2, 4);

        assertThat(held.status()).isEqualTo(status);
        assertThat(held.attemptCount()).isEqualTo(5);
        assertThat(held.nextAttemptAt()).isEqualTo(NOW.plus(LEASE));
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED"})
    @DisplayName("자동 처리가 아직 쥐고 있는(lease 안) 대기 상태는 409 ORDER_039 이고 저장하지 않는다 — 같은 환불을 겹쳐 보내지 않는다")
    void rejectsActiveLease(Claim.Status status) {
        stored(cancellation(status, NOW.plusNanos(1_000), null));

        assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_AUTO_PROCESSING);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("화면 버전이 저장된 버전과 다르면 409 ORDER_037 이고 저장하지 않는다")
    void rejectsStaleVersion() {
        stored(cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED));

        assertCode(() -> processor(true).prepareRetry(2, 3), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("버전 대조 뒤 저장에서 지면(스케줄러 · 고객 · 다른 운영자가 먼저 커밋) 409 ORDER_037 이다")
    void lostSaveIsConcurrentChange() {
        when(claims.findById(2L)).thenReturn(Optional.of(cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED)));
        when(claims.update(any())).thenThrow(new ObjectOptimisticLockingFailureException("ClaimEntity", 2L));

        assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_CONCURRENTLY_CHANGED);
    }

    @Test
    @DisplayName("처리 완료 · 주문 상태 이상 · 반품은 409 ORDER_038 이고 저장하지 않는다")
    void rejectsNonRetryableClaims() {
        for (Claim claim : List.of(cancellation(Claim.Status.COMPLETED, null, null),
            cancellation(Claim.Status.RESTORATION_FAILED, null, OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED),
            new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)),
                NOW, 4L))) {
            when(claims.findById(2L)).thenReturn(Optional.of(claim));
            assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.CLAIM_RETRY_NOT_ALLOWED);
        }
        verify(claims, never()).update(any());
    }

    @Test
    @DisplayName("재시도 스케줄러가 꺼져 있으면 읽기 전에 503 ORDER_040 이다")
    void closedWhenSchedulerDisabled() {
        assertCode(() -> processor(false).prepareRetry(2, 4), OrderErrorCode.CLAIM_RETRY_UNAVAILABLE);
        verify(claims, never()).findById(2L);
    }

    @Test
    @DisplayName("없는 클레임은 404 ORDER_032 다")
    void notFound() {
        when(claims.findById(2L)).thenReturn(Optional.empty());

        assertCode(() -> processor(true).prepareRetry(2, 4), OrderErrorCode.NOT_FOUND_CLAIM);
    }

    @Test
    @DisplayName("재시도 가능 여부는 선점과 같은 조건이다 — 스케줄러 꺼짐 · lease 안 · 완료면 false")
    void retryableMatchesPrepareRetry() {
        Claim failed = cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED);

        assertThat(processor(true).isRetryable(failed)).isTrue();
        assertThat(processor(false).isRetryable(failed)).isFalse();
        assertThat(processor(true).isRetryable(cancellation(Claim.Status.REQUESTED, NOW.plusSeconds(1), null))).isFalse();
        assertThat(processor(true).isRetryable(cancellation(Claim.Status.REQUESTED, NOW, null))).isTrue();
        assertThat(processor(true).isRetryable(cancellation(Claim.Status.COMPLETED, null, null))).isFalse();
    }

    @Test
    @DisplayName("다시 열 때 저장하는 값은 같은 클레임 아이디 · 결제 · 금액이다 — 원격은 이 값으로 멱등이다")
    void reopenKeepsRefundRequest() {
        Claim source = stored(cancellation(Claim.Status.REFUND_FAILED, null, Claim.REFUND_UNCONFIRMED));

        processor(true).prepareRetry(2, 4);

        ArgumentCaptor<Claim> saved = ArgumentCaptor.forClass(Claim.class);
        verify(claims).update(saved.capture());
        assertThat(saved.getValue().hasSameRefundRequest(source)).isTrue();
        assertThat(saved.getValue().version()).isEqualTo(4L);
    }
}
