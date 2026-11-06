package com.commercefoundry.domainlayer.order.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/** 결제 후 취소 재시도 스윕 (#16 PR-2) — 행마다 선점을 먼저 커밋하고 이긴 행만 잇는다. 한 행의 실패는 미확정 한 번으로 세고 배치를 멈추지 않는다. */
class ClaimCancellationRetrySchedulerFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    private final OrderCancellationProcessor processor = mock(OrderCancellationProcessor.class);
    private final OrderCancellationPipelineProcessor pipeline = mock(OrderCancellationPipelineProcessor.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final OrderCancellationProperties properties = new OrderCancellationProperties(false, new OrderCancellationProperties.Retry(true,
        Duration.ofMinutes(1), 50, 48, Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofMinutes(2)));
    private final ClaimCancellationRetrySchedulerFacade facade = new ClaimCancellationRetrySchedulerFacade(processor, pipeline, properties, clock);

    private static Claim claim(long orderId) {
        Claim claim = mock(Claim.class);
        when(claim.orderId()).thenReturn(orderId);
        return claim;
    }

    @Test
    @DisplayName("선점에서 진 행은 원격 호출 없이 건너뛰고, 이긴 행은 선점한 클레임(올라간 버전)으로 잇는다")
    void skipsRowsLostOnHold() {
        Claim lost = claim(1);
        Claim won = claim(2);
        Claim held = claim(2);
        when(processor.findRetryTargets()).thenReturn(List.of(lost, won));
        when(processor.hold(lost)).thenThrow(new ObjectOptimisticLockingFailureException("claim", 1L));
        when(processor.hold(won)).thenReturn(held);

        facade.retryDueCancellations();

        verify(pipeline, never()).progress(lost);
        verify(pipeline, never()).progress(won);
        verify(pipeline).progress(held);
        verify(processor, never()).recordAttemptFailure(anyLong());
    }

    @Test
    @DisplayName("파이프라인이 낙관적 락이 아닌 예외로 실패하면 그 행을 미확정 한 번으로 세고(기록 실패도 삼킨다) 나머지 행을 잇는다")
    void failureIsCountedAndBatchContinues() {
        Claim holdFails = claim(1);
        Claim progressFails = claim(2);
        Claim countFails = claim(3);
        Claim ok = claim(4);
        Claim heldFailing = claim(2);
        Claim heldCountFails = claim(3);
        Claim heldOk = claim(4);
        when(processor.findRetryTargets()).thenReturn(List.of(holdFails, progressFails, countFails, ok));
        when(processor.hold(holdFails)).thenThrow(new CannotCreateTransactionException("db"));
        when(processor.hold(progressFails)).thenReturn(heldFailing);
        when(processor.hold(countFails)).thenReturn(heldCountFails);
        when(processor.hold(ok)).thenReturn(heldOk);
        when(pipeline.progress(heldFailing)).thenThrow(new IllegalStateException("boom"));
        when(pipeline.progress(heldCountFails)).thenThrow(new CannotCreateTransactionException("db"));
        doThrow(new CannotCreateTransactionException("db")).when(processor).recordAttemptFailure(3L);

        facade.retryDueCancellations();

        verify(processor).recordAttemptFailure(2L);
        verify(processor).recordAttemptFailure(3L);
        verify(processor, never()).recordAttemptFailure(1L);
        verify(pipeline).progress(heldOk);
    }

    @Test
    @DisplayName("한 번 실행이 lease 의 절반을 넘기면 남은 행은 선점하지 않고 다음 실행으로 넘긴다")
    void stopsAtTimeBudget() {
        Claim first = claim(1);
        Claim second = claim(2);
        Claim held = claim(1);
        when(processor.findRetryTargets()).thenReturn(List.of(first, second));
        when(processor.hold(first)).thenReturn(held);
        when(pipeline.progress(held)).thenAnswer(invocation -> {
            now.set(NOW.plus(Duration.ofMinutes(1)).plusSeconds(1));
            return held;
        });

        facade.retryDueCancellations();

        verify(pipeline).progress(held);
        verify(processor, never()).hold(second);
    }

    @Test
    @DisplayName("대상이 없으면 선점 · 원격 호출을 하지 않는다")
    void emptyBatch() {
        when(processor.findRetryTargets()).thenReturn(List.of());

        facade.retryDueCancellations();

        verify(processor, never()).hold(any());
        verify(pipeline, never()).progress(any());
    }
}
