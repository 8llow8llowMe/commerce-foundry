package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.model.OrderExpiryCandidate;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderExpiryProcessor;
import com.commercefoundry.global.properties.OrderPaymentExpiryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(OutputCaptureExtension.class)
class OrderSchedulerFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    private final OrderExpiryProcessor processor = mock(OrderExpiryProcessor.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private OrderSchedulerFacade facade(int size, int batches) {
        return facade(size, batches, clock);
    }

    private OrderSchedulerFacade facade(int size, int batches, Clock clock) {
        return new OrderSchedulerFacade(processor, new OrderPaymentExpiryProperties(true, Duration.ofSeconds(30), size, batches), clock);
    }

    @Test
    @DisplayName("한 건이 실패해도 커서를 넘겨 뒤 건을 처리하고, 다음 실행은 처음부터 다시 읽어 실패 건을 재시도한다")
    void failureAdvancesCursorAndNextSweepRetriesFromStart() {
        var a = new OrderExpiryCandidate(1, NOW);
        var b = new OrderExpiryCandidate(2, NOW);
        var c = new OrderExpiryCandidate(3, NOW);
        when(processor.findDue(NOW, null, 2)).thenReturn(List.of(a, b));
        when(processor.findDue(NOW, b, 2)).thenReturn(List.of(c));
        when(processor.expire(1, NOW)).thenThrow(new IllegalStateException("SimulatedFailure"));
        when(processor.expire(2, NOW)).thenThrow(new OptimisticLockingFailureException("SimulatedConflict"));
        facade(2, 10).expireOverdueOrders();
        facade(2, 10).expireOverdueOrders();
        verify(processor, times(2)).expire(1, NOW);
        verify(processor, times(2)).expire(2, NOW);
        verify(processor, times(2)).expire(3, NOW);
        verify(processor, times(2)).findDue(NOW, null, 2);
        verify(processor, times(2)).findDue(NOW, b, 2);
    }

    @Test
    @DisplayName("한 실행은 설정한 배치 수까지만 돌고, 빈 배치면 바로 끝난다")
    void capsWorkAtConfiguredBatchCountAndReturnsOnEmptyPage() {
        when(processor.findDue(eq(NOW), any(), eq(1))).thenReturn(List.of(new OrderExpiryCandidate(1, NOW)));
        facade(1, 10).expireOverdueOrders();
        verify(processor, times(10)).expire(1, NOW);
        reset(processor);
        when(processor.findDue(NOW, null, 1)).thenReturn(List.of());
        facade(1, 10).expireOverdueOrders();
        verify(processor, never()).expire(anyLong(), any());
    }

    @Test
    @DisplayName("기준 시각은 마이크로초로 잘라 조회 · 만료에 같은 값을 넘긴다 — DB timestamp(6) 와 도메인 판정이 경계에서 갈리지 않게")
    void truncatesNowToMicrosForQueryAndExpiry() {
        Instant micros = NOW.plusNanos(1_234_000);
        when(processor.findDue(micros, null, 10)).thenReturn(List.of(new OrderExpiryCandidate(1, NOW)));
        facade(10, 1, Clock.fixed(NOW.plusNanos(1_234_567), ZoneOffset.UTC)).expireOverdueOrders();
        verify(processor).findDue(micros, null, 10);
        verify(processor).expire(1, micros);
    }

    @Test
    @DisplayName("실패 로그는 예외 타입 · 루트 원인 타입만 남기고 원인 사슬의 메시지(행 값)는 남기지 않는다")
    void failureLogOmitsCauseMessages(CapturedOutput output) {
        when(processor.findDue(NOW, null, 10)).thenReturn(List.of(new OrderExpiryCandidate(1, NOW)));
        when(processor.expire(1, NOW)).thenThrow(new DataIntegrityViolationException("outer secret-outer",
            new IllegalStateException("Failing row contains (secret-row)")));
        facade(10, 1).expireOverdueOrders();
        assertThat(output.getAll())
            .contains("order expire failed. orderId=1, error=DataIntegrityViolationException, rootCause=IllegalStateException")
            .doesNotContain("secret-row")
            .doesNotContain("secret-outer");
    }
}
