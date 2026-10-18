package com.commercefoundry.domainlayer.product.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.StockSchedulerUseCase;
import com.commercefoundry.global.properties.StockProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class StockReservationExpirySchedulerTest {

    private static final int BATCH_SIZE = 2;
    private static final int MAX_BATCHES = 3;

    private StockSchedulerUseCase stockSchedulerUseCase;
    private StockReservationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        stockSchedulerUseCase = mock(StockSchedulerUseCase.class);
        StockProperties properties = new StockProperties(new StockProperties.Reservation(
            Duration.ofMinutes(30), new StockProperties.ExpirySweep(Duration.ofSeconds(60), Duration.ofSeconds(60), BATCH_SIZE, MAX_BATCHES)
        ));
        scheduler = new StockReservationExpiryScheduler(stockSchedulerUseCase, properties);
    }

    @Test
    @DisplayName("배치가 가득 차지 않으면 한 번만 읽고 건마다 만료를 부른다")
    void singleBatch() {
        given(stockSchedulerUseCase.findExpiredReservationIds(BATCH_SIZE)).willReturn(List.of(11L));
        given(stockSchedulerUseCase.expire(11L)).willReturn(true);

        scheduler.expireReservations();

        verify(stockSchedulerUseCase, times(1)).findExpiredReservationIds(BATCH_SIZE);
        verify(stockSchedulerUseCase).expire(11L);
        verifyNoMoreInteractions(stockSchedulerUseCase);
    }

    @Test
    @DisplayName("배치가 가득 차면 다음 배치를 읽는다 — 덜 찬 배치에서 멈춘다")
    void repeatsWhileFull() {
        given(stockSchedulerUseCase.findExpiredReservationIds(BATCH_SIZE)).willReturn(List.of(11L, 12L), List.of(13L));
        given(stockSchedulerUseCase.expire(anyLong())).willReturn(true);

        scheduler.expireReservations();

        verify(stockSchedulerUseCase, times(2)).findExpiredReservationIds(BATCH_SIZE);
        verify(stockSchedulerUseCase).expire(11L);
        verify(stockSchedulerUseCase).expire(12L);
        verify(stockSchedulerUseCase).expire(13L);
    }

    @Test
    @DisplayName("배치가 계속 가득 차도 한 실행은 max-batches-per-run 번까지만 읽는다")
    void stopsAtMaxBatches() {
        given(stockSchedulerUseCase.findExpiredReservationIds(BATCH_SIZE)).willReturn(List.of(11L, 12L));

        scheduler.expireReservations();

        verify(stockSchedulerUseCase, times(MAX_BATCHES)).findExpiredReservationIds(BATCH_SIZE);
    }

    @Test
    @DisplayName("빈 배치면 만료를 부르지 않는다")
    void empty() {
        given(stockSchedulerUseCase.findExpiredReservationIds(BATCH_SIZE)).willReturn(List.of());

        scheduler.expireReservations();

        verify(stockSchedulerUseCase).findExpiredReservationIds(BATCH_SIZE);
        verifyNoMoreInteractions(stockSchedulerUseCase);
    }

    @Test
    @DisplayName("한 건이 실패(409 · 그 밖의 예외)해도 같은 배치의 다음 건을 계속 푼다 — 예외가 스케줄러 밖으로 나가지 않는다")
    void isolatesFailures() {
        given(stockSchedulerUseCase.findExpiredReservationIds(BATCH_SIZE)).willReturn(List.of(11L, 12L), List.of(13L));
        given(stockSchedulerUseCase.expire(11L)).willThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT));
        given(stockSchedulerUseCase.expire(12L)).willThrow(new IllegalStateException("boom"));
        given(stockSchedulerUseCase.expire(13L)).willReturn(true);

        assertThatCode(scheduler::expireReservations).doesNotThrowAnyException();

        verify(stockSchedulerUseCase).expire(11L);
        verify(stockSchedulerUseCase).expire(12L);
        verify(stockSchedulerUseCase).expire(13L);
    }

    @Test
    @DisplayName("주기는 설정 키(stock.reservation.expiry-sweep.*)에서 읽는다")
    void scheduleUsesProperties() throws NoSuchMethodException {
        Method method = StockReservationExpiryScheduler.class.getMethod("expireReservations");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${stock.reservation.expiry-sweep.fixed-delay}");
        assertThat(scheduled.initialDelayString()).isEqualTo("${stock.reservation.expiry-sweep.initial-delay}");
    }
}
