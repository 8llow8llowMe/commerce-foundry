package com.commercefoundry.domainlayer.product.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ExpiredStockReservationInfo;
import com.commercefoundry.domainlayer.product.application.port.in.StockSchedulerUseCase;
import com.commercefoundry.global.properties.StockProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(OutputCaptureExtension.class)
class StockReservationExpirySchedulerTest {

    private static final int BATCH_SIZE = 2;
    private static final int MAX_BATCHES = 3;
    private static final Instant EXPIRED_AT = Instant.parse("2026-10-18T09:00:00Z");

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
    @DisplayName("배치가 가득 차지 않으면 처음부터(커서 null) 한 번만 읽고 건마다 만료를 부른다")
    void singleBatch() {
        given(stockSchedulerUseCase.findExpiredReservations(null, BATCH_SIZE)).willReturn(List.of(target(11L)));
        given(stockSchedulerUseCase.expire(11L)).willReturn(true);

        scheduler.expireReservations();

        verify(stockSchedulerUseCase, times(1)).findExpiredReservations(null, BATCH_SIZE);
        verify(stockSchedulerUseCase).expire(11L);
        verifyNoMoreInteractions(stockSchedulerUseCase);
    }

    @Test
    @DisplayName("배치가 가득 차면 앞 배치의 마지막 건을 커서로 다음 배치를 읽는다 — 덜 찬 배치에서 멈춘다")
    void repeatsWithCursorWhileFull() {
        given(stockSchedulerUseCase.findExpiredReservations(null, BATCH_SIZE)).willReturn(List.of(target(11L), target(12L)));
        given(stockSchedulerUseCase.findExpiredReservations(target(12L), BATCH_SIZE)).willReturn(List.of(target(13L)));
        given(stockSchedulerUseCase.expire(anyLong())).willReturn(true);

        scheduler.expireReservations();

        InOrder order = inOrder(stockSchedulerUseCase);
        order.verify(stockSchedulerUseCase).findExpiredReservations(null, BATCH_SIZE);
        order.verify(stockSchedulerUseCase).expire(11L);
        order.verify(stockSchedulerUseCase).expire(12L);
        order.verify(stockSchedulerUseCase).findExpiredReservations(target(12L), BATCH_SIZE);
        order.verify(stockSchedulerUseCase).expire(13L);
        verifyNoMoreInteractions(stockSchedulerUseCase);
    }

    @Test
    @DisplayName("앞 배치가 모두 실패(RESERVED 로 남음)해도 커서가 넘어가 같은 실행이 그 건들을 다시 읽지 않는다 — head-of-line blocking 없음")
    void failedHeadDoesNotBlock() {
        given(stockSchedulerUseCase.findExpiredReservations(null, BATCH_SIZE)).willReturn(List.of(target(11L), target(12L)));
        given(stockSchedulerUseCase.findExpiredReservations(target(12L), BATCH_SIZE)).willReturn(List.of(target(13L)));
        given(stockSchedulerUseCase.expire(11L)).willThrow(new IllegalStateException("boom"));
        given(stockSchedulerUseCase.expire(12L)).willThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT));
        given(stockSchedulerUseCase.expire(13L)).willReturn(true);

        scheduler.expireReservations();

        verify(stockSchedulerUseCase, times(1)).expire(11L);
        verify(stockSchedulerUseCase, times(1)).expire(12L);
        verify(stockSchedulerUseCase).expire(13L);
        verify(stockSchedulerUseCase, times(1)).findExpiredReservations(isNull(), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("배치가 계속 가득 차도 한 실행은 max-batches-per-run 번까지만 읽는다")
    void stopsAtMaxBatches() {
        given(stockSchedulerUseCase.findExpiredReservations(any(), eq(BATCH_SIZE))).willReturn(List.of(target(11L), target(12L)));

        scheduler.expireReservations();

        verify(stockSchedulerUseCase, times(MAX_BATCHES)).findExpiredReservations(any(), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("빈 배치면 만료를 부르지 않는다")
    void empty() {
        given(stockSchedulerUseCase.findExpiredReservations(null, BATCH_SIZE)).willReturn(List.of());

        scheduler.expireReservations();

        verify(stockSchedulerUseCase).findExpiredReservations(null, BATCH_SIZE);
        verifyNoMoreInteractions(stockSchedulerUseCase);
    }

    @Test
    @DisplayName("한 건이 실패해도 다음 건을 계속 푼다. 409 충돌(PRODUCT_026)은 WARN, 그 밖의 업무 오류 · 예상 못 한 예외는 ERROR 이고 오류 코드를 남긴다")
    void isolatesFailuresAndLogsByKind(CapturedOutput output) {
        given(stockSchedulerUseCase.findExpiredReservations(null, BATCH_SIZE)).willReturn(List.of(target(11L), target(12L)));
        given(stockSchedulerUseCase.findExpiredReservations(target(12L), BATCH_SIZE)).willReturn(List.of(target(13L), target(14L)));
        given(stockSchedulerUseCase.findExpiredReservations(target(14L), BATCH_SIZE)).willReturn(List.of());
        given(stockSchedulerUseCase.expire(11L)).willThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT));
        given(stockSchedulerUseCase.expire(12L)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_STOCK_RESERVATION));
        given(stockSchedulerUseCase.expire(13L)).willThrow(new IllegalStateException("boom"));
        given(stockSchedulerUseCase.expire(14L)).willReturn(true);

        assertThatCode(scheduler::expireReservations).doesNotThrowAnyException();

        verify(stockSchedulerUseCase).expire(14L);
        assertThat(logLine(output, "reservationId=11")).contains("WARN", "errorCode=PRODUCT_026");
        assertThat(logLine(output, "reservationId=12")).contains("ERROR", "errorCode=PRODUCT_003");
        assertThat(logLine(output, "reservationId=13")).contains("ERROR", "error=IllegalStateException");
    }

    @Test
    @DisplayName("주기는 설정 키(stock.reservation.expiry-sweep.*)에서 읽는다")
    void scheduleUsesProperties() throws NoSuchMethodException {
        Method method = StockReservationExpiryScheduler.class.getMethod("expireReservations");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${stock.reservation.expiry-sweep.fixed-delay}");
        assertThat(scheduled.initialDelayString()).isEqualTo("${stock.reservation.expiry-sweep.initial-delay}");
    }

    /** 만료 시각은 아이디 순으로 1초씩 — 커서 값을 아이디로 구분한다. */
    private static ExpiredStockReservationInfo target(long reservationId) {
        return new ExpiredStockReservationInfo(reservationId, EXPIRED_AT.plusSeconds(reservationId));
    }

    private static String logLine(CapturedOutput output, String marker) {
        List<String> lines = output.getOut().lines().filter(line -> line.contains("stock reservation expire") && line.contains(marker)).toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }
}
