package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.command.StockRestorationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.StockRestorationInfo;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuRestoredQuantityQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationItem;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockRestoration;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationItem;
import com.commercefoundry.domainlayer.product.domain.model.StockRestorationReason;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class StockRestorationProcessorTest {

    private static final long CLAIM_ID = 6001L;
    private static final long RESERVATION_ID = 7001L;
    private static final long ORDER_ID = 8001L;
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    private StockReservationRepositoryPort stockReservationRepositoryPort;
    private StockRestorationRepositoryPort stockRestorationRepositoryPort;
    private StockRepositoryPort stockRepositoryPort;
    private StockRestorationProcessor processor;

    @BeforeEach
    void setUp() {
        stockReservationRepositoryPort = Mockito.mock(StockReservationRepositoryPort.class);
        stockRestorationRepositoryPort = Mockito.mock(StockRestorationRepositoryPort.class);
        stockRepositoryPort = Mockito.mock(StockRepositoryPort.class);
        processor = new StockRestorationProcessor(stockReservationRepositoryPort, stockRestorationRepositoryPort, stockRepositoryPort, new SequenceIdGenerator());
    }

    @Nested
    @DisplayName("신규 복원")
    class Create {

        @BeforeEach
        void setUp() {
            givenReservation(StockReservationStatus.CONFIRMED, item(1001L, 3, true), item(1002L, 2, false), item(1003L, 1, true));
            given(stockRestorationRepositoryPort.findByClaimId(CLAIM_ID)).willReturn(Optional.empty());
            given(stockRepositoryPort.restore(anyLong(), anyLong())).willReturn(true);
        }

        @Test
        @DisplayName("잠금 읽기 → 클레임 조회 → 기복원 합 → 기록 INSERT → 차감했던 SKU 만 오름차순 복원 순이다. 같은 SKU 는 합친다")
        void restoresInOrder() {
            StockRestorationInfo info = processor.restore(command(StockRestorationReason.CANCELLATION, item(1003L, 1), item(1001L, 1), item(1002L, 2), item(1001L, 1)));

            StockRestoration saved = createdRestoration();
            assertThat(saved.claimId()).isEqualTo(CLAIM_ID);
            assertThat(saved.reservationId()).isEqualTo(RESERVATION_ID);
            assertThat(saved.orderId()).as("선점에서 복사한다").isEqualTo(ORDER_ID);
            assertThat(saved.reason()).isEqualTo(StockRestorationReason.CANCELLATION);
            assertThat(saved.items()).extracting(StockRestorationItem::skuId, StockRestorationItem::quantity, StockRestorationItem::restored)
                .containsExactly(tuple(1001L, 2, true), tuple(1002L, 2, false), tuple(1003L, 1, true));

            InOrder order = inOrder(stockReservationRepositoryPort, stockRestorationRepositoryPort, stockRepositoryPort);
            order.verify(stockReservationRepositoryPort).findByIdForUpdate(RESERVATION_ID);
            order.verify(stockRestorationRepositoryPort).findByClaimId(CLAIM_ID);
            order.verify(stockRestorationRepositoryPort).sumRestoredQuantitiesByReservationId(RESERVATION_ID);
            order.verify(stockRestorationRepositoryPort).create(any());
            order.verify(stockRepositoryPort).restore(1001L, 2L);
            order.verify(stockRepositoryPort).restore(1003L, 1L);
            // 무제한(deducted=false) SKU 는 기록만 남기고 재고를 바꾸지 않는다
            verify(stockRepositoryPort, never()).restore(1002L, 2L);
            // 잠금 없는 단건 조회는 쓰지 않는다
            verify(stockReservationRepositoryPort, never()).findById(anyLong());

            assertThat(info.restorationId()).isEqualTo(saved.id());
            assertThat(info.items()).extracting(StockRestorationInfo.Item::skuId, StockRestorationInfo.Item::restored)
                .containsExactly(tuple(1001L, true), tuple(1002L, false), tuple(1003L, true));
        }

        @Test
        @DisplayName("이미 복원한 수량을 빼고 남은 만큼(경계 포함)은 받는다 — 부분 클레임이 여러 번 온다")
        void partialAfterPartial() {
            given(stockRestorationRepositoryPort.sumRestoredQuantitiesByReservationId(RESERVATION_ID))
                .willReturn(List.of(new SkuRestoredQuantityQueryResult(1001L, 2L)));

            processor.restore(command(StockRestorationReason.RETURN, item(1001L, 1)));

            verify(stockRepositoryPort).restore(1001L, 1L);
        }

        @Test
        @DisplayName("재고 행이 없어 복원이 0행이어도 막지 않는다 — 해제 경로와 같이 기록은 남기고 경고만 남긴다")
        void missingStockRowIsSkipped() {
            given(stockRepositoryPort.restore(1001L, 1L)).willReturn(false);

            StockRestorationInfo info = processor.restore(command(StockRestorationReason.CANCELLATION, item(1001L, 1), item(1003L, 1)));

            verify(stockRestorationRepositoryPort).create(any());
            verify(stockRepositoryPort).restore(1003L, 1L);
            assertThat(info.items()).hasSize(2);
        }

        @Test
        @DisplayName("클레임 동시 생성(유니크 키)이면 어댑터의 409 PRODUCT_030 이 그대로 나가고 재고를 건드리지 않는다")
        void conflictPropagates() {
            doThrow(new ProductException(ProductErrorCode.STOCK_RESTORATION_CONFLICT)).when(stockRestorationRepositoryPort).create(any());

            assertFailsWith(() -> processor.restore(command(StockRestorationReason.CANCELLATION, item(1001L, 1))), ProductErrorCode.STOCK_RESTORATION_CONFLICT);
            verifyNoInteractions(stockRepositoryPort);
        }
    }

    @Nested
    @DisplayName("상한 초과")
    class Exceeded {

        @BeforeEach
        void setUp() {
            givenReservation(StockReservationStatus.CONFIRMED, item(1001L, 3, true), item(1002L, 2, false));
            given(stockRestorationRepositoryPort.findByClaimId(CLAIM_ID)).willReturn(Optional.empty());
        }

        @Test
        @DisplayName("기복원을 뺀 상한을 넘는 SKU 와 선점에 없는 SKU 를 모두 모아 409 PRODUCT_028 이고 아무것도 쓰지 않는다")
        void collectsAllExceeded() {
            given(stockRestorationRepositoryPort.sumRestoredQuantitiesByReservationId(RESERVATION_ID))
                .willReturn(List.of(new SkuRestoredQuantityQueryResult(1001L, 3L), new SkuRestoredQuantityQueryResult(1002L, 1L)));

            assertThatThrownBy(() -> processor.restore(command(StockRestorationReason.RETURN, item(1001L, 1), item(1002L, 1), item(9999L, 1))))
                .isInstanceOf(ProductException.class)
                .hasMessage("복원 가능 수량을 넘은 SKU 가 있습니다: [1001, 9999]")
                .extracting(exception -> ((ProductException) exception).getErrorCode())
                .isEqualTo(ProductErrorCode.STOCK_RESTORATION_QUANTITY_EXCEEDED);
            verify(stockRestorationRepositoryPort, never()).create(any());
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("복원한 적이 없어도 확정 수량을 넘으면 409 PRODUCT_028 이다")
        void overConfirmedQuantity() {
            given(stockRestorationRepositoryPort.sumRestoredQuantitiesByReservationId(RESERVATION_ID)).willReturn(List.of());

            assertFailsWith(() -> processor.restore(command(StockRestorationReason.CANCELLATION, item(1001L, 4))), ProductErrorCode.STOCK_RESTORATION_QUANTITY_EXCEEDED);
            verifyNoInteractions(stockRepositoryPort);
        }
    }

    @Nested
    @DisplayName("같은 클레임의 재요청")
    class SameClaim {

        private final StockRestoration existing = new StockRestoration(90_500L, CLAIM_ID, RESERVATION_ID, ORDER_ID, StockRestorationReason.CANCELLATION, List.of(
            new StockRestorationItem(90_501L, 1001L, 2, true), new StockRestorationItem(90_502L, 1002L, 1, false)
        ));

        @BeforeEach
        void setUp() {
            given(stockRestorationRepositoryPort.findByClaimId(CLAIM_ID)).willReturn(Optional.of(existing));
        }

        @Test
        @DisplayName("내용이 같으면 기존 복원을 그대로 돌려주고 재고 · 기록을 건드리지 않는다 (멱등, 항목 순서 · 분할은 무관)")
        void sameReturnsExisting() {
            givenReservation(StockReservationStatus.CONFIRMED, item(1001L, 3, true), item(1002L, 2, false));

            StockRestorationInfo info = processor.restore(command(StockRestorationReason.CANCELLATION, item(1002L, 1), item(1001L, 1), item(1001L, 1)));

            assertThat(info).isEqualTo(StockRestorationInfo.from(existing));
            verify(stockRestorationRepositoryPort, never()).sumRestoredQuantitiesByReservationId(anyLong());
            verify(stockRestorationRepositoryPort, never()).create(any());
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("재요청은 선점 상태와 무관하게 처음 결과다 — 상태 확인보다 기존 복원 확인이 먼저다")
        void existingWinsOverStatus() {
            givenReservation(StockReservationStatus.RELEASED, item(1001L, 3, true), item(1002L, 2, false));

            assertThat(processor.restore(command(StockRestorationReason.CANCELLATION, item(1001L, 2), item(1002L, 1))).restorationId()).isEqualTo(90_500L);
        }

        @Test
        @DisplayName("수량 · 사유 · 선점이 다르면 409 PRODUCT_029 다")
        void differentIsMismatch() {
            givenReservation(StockReservationStatus.CONFIRMED, item(1001L, 3, true), item(1002L, 2, false));
            given(stockReservationRepositoryPort.findByIdForUpdate(7002L)).willReturn(Optional.of(reservation(7002L, StockReservationStatus.CONFIRMED, item(1001L, 3, true))));

            assertFailsWith(() -> processor.restore(command(StockRestorationReason.CANCELLATION, item(1001L, 1), item(1002L, 1))), ProductErrorCode.STOCK_RESTORATION_MISMATCH);
            assertFailsWith(() -> processor.restore(command(StockRestorationReason.RETURN, item(1001L, 2), item(1002L, 1))), ProductErrorCode.STOCK_RESTORATION_MISMATCH);
            assertFailsWith(
                () -> processor.restore(new StockRestorationCommand(CLAIM_ID, 7002L, StockRestorationReason.CANCELLATION, List.of(item(1001L, 2), item(1002L, 1)))),
                ProductErrorCode.STOCK_RESTORATION_MISMATCH
            );
            verify(stockRestorationRepositoryPort, never()).create(any());
            verifyNoInteractions(stockRepositoryPort);
        }
    }

    @Nested
    @DisplayName("선점 상태")
    class ReservationState {

        @ParameterizedTest
        @EnumSource(value = StockReservationStatus.class, names = {"RESERVED", "RELEASED"})
        @DisplayName("확정이 아닌 선점(결제 전 · 해제)은 409 PRODUCT_027 이고 아무것도 쓰지 않는다")
        void notConfirmed(StockReservationStatus status) {
            givenReservation(status, item(1001L, 3, true));
            given(stockRestorationRepositoryPort.findByClaimId(CLAIM_ID)).willReturn(Optional.empty());

            assertFailsWith(() -> processor.restore(command(StockRestorationReason.CANCELLATION, item(1001L, 1))), ProductErrorCode.STOCK_RESTORATION_RESERVATION_NOT_CONFIRMED);
            verify(stockRestorationRepositoryPort, never()).create(any());
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("없는 선점은 404 PRODUCT_003 이고 클레임도 조회하지 않는다")
        void notFound() {
            given(stockReservationRepositoryPort.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.empty());

            assertFailsWith(() -> processor.restore(command(StockRestorationReason.CANCELLATION, item(1001L, 1))), ProductErrorCode.NOT_FOUND_STOCK_RESERVATION);
            verifyNoInteractions(stockRestorationRepositoryPort, stockRepositoryPort);
        }
    }

    private void givenReservation(StockReservationStatus status, StockReservationItem... items) {
        given(stockReservationRepositoryPort.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation(RESERVATION_ID, status, items)));
    }

    private StockRestoration createdRestoration() {
        ArgumentCaptor<StockRestoration> captor = ArgumentCaptor.forClass(StockRestoration.class);
        verify(stockRestorationRepositoryPort).create(captor.capture());
        return captor.getValue();
    }

    private static StockReservation reservation(long reservationId, StockReservationStatus status, StockReservationItem... items) {
        Instant confirmedAt = status == StockReservationStatus.CONFIRMED ? NOW.minusSeconds(60) : null;
        Instant releasedAt = status == StockReservationStatus.RELEASED ? NOW.minusSeconds(60) : null;
        StockReleaseReason reason = status == StockReservationStatus.RELEASED ? StockReleaseReason.RELEASED_BY_CALLER : null;
        return new StockReservation(reservationId, ORDER_ID, status, NOW.plusSeconds(1800), confirmedAt, releasedAt, reason, List.of(items), 1L);
    }

    private static StockReservationItem item(long skuId, int quantity, boolean deducted) {
        return new StockReservationItem(skuId + 50_000L, skuId, quantity, deducted);
    }

    private static StockRestorationCommand.Item item(long skuId, int quantity) {
        return new StockRestorationCommand.Item(skuId, quantity);
    }

    private static StockRestorationCommand command(StockRestorationReason reason, StockRestorationCommand.Item... items) {
        return new StockRestorationCommand(CLAIM_ID, RESERVATION_ID, reason, List.of(items));
    }

    private static void assertFailsWith(Runnable call, ProductErrorCode errorCode) {
        assertThatThrownBy(call::run)
            .isInstanceOf(ProductException.class)
            .extracting(exception -> ((ProductException) exception).getErrorCode())
            .isEqualTo(errorCode);
    }

    /** 순서대로 아이디를 낸다 — Snowflake 는 시각 · 워커에 기대므로 테스트에서 값을 고정한다. */
    private static final class SequenceIdGenerator extends SnowflakeIdGenerator {

        private final AtomicLong next = new AtomicLong(90_000L);

        SequenceIdGenerator() {
            super(0, 0);
        }

        @Override
        public synchronized long generateId() {
            return next.incrementAndGet();
        }
    }
}
