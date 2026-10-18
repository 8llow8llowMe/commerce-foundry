package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.domainlayer.product.support.ProductFixtures.product;
import static com.commercefoundry.domainlayer.product.support.ProductFixtures.sku;
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

import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ExpiredStockReservationInfo;
import com.commercefoundry.domainlayer.product.application.info.StockReservationInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockReservationRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ExpiredStockReservationQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.domain.model.StockReleaseReason;
import com.commercefoundry.domainlayer.product.domain.model.StockReservation;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationItem;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationStatus;
import com.commercefoundry.domainlayer.product.domain.model.StockReservationTransitionException;
import com.commercefoundry.global.properties.StockProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class StockReservationProcessorTest {

    private static final long ORDER_ID = 8001L;
    private static final long RESERVATION_ID = 7001L;
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final long PRODUCT_ID = 501L;

    private StockReservationRepositoryPort stockReservationRepositoryPort;
    private StockRepositoryPort stockRepositoryPort;
    private SkuRepositoryPort skuRepositoryPort;
    private ProductRepositoryPort productRepositoryPort;
    private StockReservationProcessor processor;

    @BeforeEach
    void setUp() {
        stockReservationRepositoryPort = Mockito.mock(StockReservationRepositoryPort.class);
        stockRepositoryPort = Mockito.mock(StockRepositoryPort.class);
        skuRepositoryPort = Mockito.mock(SkuRepositoryPort.class);
        productRepositoryPort = Mockito.mock(ProductRepositoryPort.class);
        StockProperties properties = new StockProperties(new StockProperties.Reservation(
            TTL, new StockProperties.ExpirySweep(Duration.ofSeconds(60), Duration.ofSeconds(60), 100, 10)
        ));
        processor = new StockReservationProcessor(
            stockReservationRepositoryPort, stockRepositoryPort, skuRepositoryPort, productRepositoryPort,
            new SequenceIdGenerator(), properties, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Nested
    @DisplayName("선점")
    class Reserve {

        @BeforeEach
        void setUp() {
            given(stockReservationRepositoryPort.findActiveByOrderId(ORDER_ID)).willReturn(Optional.empty());
        }

        @Test
        @DisplayName("같은 SKU 는 수량을 합치고 헤더 → SKU 오름차순 차감 → 항목 순으로 쓴다 — 만료 시각은 지금 + ttl")
        void mergesAndSorts() {
            givenOnSale(1003L, 1001L);
            given(stockRepositoryPort.deduct(anyLong(), anyLong())).willReturn(true);

            StockReservationInfo info = processor.reserve(command(item(1003L, 1), item(1001L, 2), item(1003L, 2)));

            StockReservation saved = savedReservation();
            assertThat(saved.items()).extracting(StockReservationItem::skuId, StockReservationItem::quantity, StockReservationItem::deducted)
                .containsExactly(tuple(1001L, 2, true), tuple(1003L, 3, true));
            assertThat(saved.status()).isEqualTo(StockReservationStatus.RESERVED);
            assertThat(saved.orderId()).isEqualTo(ORDER_ID);
            assertThat(saved.expiresAt()).isEqualTo(NOW.plus(TTL));

            InOrder order = inOrder(stockReservationRepositoryPort, stockRepositoryPort);
            order.verify(stockReservationRepositoryPort).createHeader(saved.id(), ORDER_ID, NOW.plus(TTL));
            order.verify(stockRepositoryPort).deduct(1001L, 2L);
            order.verify(stockRepositoryPort).deduct(1003L, 3L);
            order.verify(stockReservationRepositoryPort).createItems(any());
            // 모두 차감됐으면 재고 행을 다시 읽지 않는다
            verify(stockRepositoryPort, never()).findAllBySkuIds(any());

            assertThat(info.reservationId()).isEqualTo(saved.id());
            assertThat(info.status()).isEqualTo(StockReservationStatus.RESERVED);
            assertThat(info.items()).extracting(StockReservationInfo.Item::skuId).containsExactly(1001L, 1003L);
        }

        @Test
        @DisplayName("차감이 0행인 SKU 를 다시 읽어 무제한이면 deducted=false 로 선점한다 — 다시 읽기는 0행 SKU 만 in 절 한 번이다")
        void unlimitedIsNotDeducted() {
            givenOnSale(1001L, 1002L);
            given(stockRepositoryPort.deduct(1001L, 1L)).willReturn(true);
            given(stockRepositoryPort.deduct(1002L, 5L)).willReturn(false);
            given(stockRepositoryPort.findAllBySkuIds(Set.of(1002L))).willReturn(List.of(new Stock(1002L, false, 0L)));

            StockReservationInfo info = processor.reserve(command(item(1001L, 1), item(1002L, 5)));

            assertThat(info.items()).extracting(StockReservationInfo.Item::deducted).containsExactly(true, false);
            assertThat(savedReservation().deductedItems()).extracting(StockReservationItem::skuId).containsExactly(1001L);
            verify(stockRepositoryPort).findAllBySkuIds(Set.of(1002L));
        }

        @Test
        @DisplayName("미리 무제한이던 SKU 를 그 사이 어드민이 관리로 바꿨으면 조건부 차감이 1행이라 deducted=true 다 (스냅숏 무제한 → 실제 관리)")
        void becameManagedIsDeducted() {
            givenOnSale(1001L);
            // 스냅숏을 미리 읽지 않는다 — 차감 결과가 곧 판정이다
            given(stockRepositoryPort.deduct(1001L, 2L)).willReturn(true);

            StockReservationInfo info = processor.reserve(command(item(1001L, 2)));

            assertThat(info.items()).extracting(StockReservationInfo.Item::deducted).containsExactly(true);
            verify(stockRepositoryPort, never()).findAllBySkuIds(any());
        }

        @Test
        @DisplayName("관리 중이던 SKU 를 그 사이 어드민이 무제한으로 바꿨으면 차감 0행 뒤 다시 읽어 무제한이라 deducted=false 로 선점한다 (관리 → 무제한)")
        void becameUnlimitedIsNotDeducted() {
            givenOnSale(1001L);
            given(stockRepositoryPort.deduct(1001L, 2L)).willReturn(false);
            given(stockRepositoryPort.findAllBySkuIds(Set.of(1001L))).willReturn(List.of(new Stock(1001L, false, 0L)));

            StockReservationInfo info = processor.reserve(command(item(1001L, 2)));

            assertThat(info.items()).extracting(StockReservationInfo.Item::deducted).containsExactly(false);
        }

        @Test
        @DisplayName("재고 행이 없는 SKU 는 품절이다 — 차감 0행 · 다시 읽어도 행이 없어 부족으로 모이고 항목을 쓰지 않는다")
        void missingStockRowIsInsufficient() {
            givenOnSale(1001L);
            given(stockRepositoryPort.deduct(1001L, 1L)).willReturn(false);
            given(stockRepositoryPort.findAllBySkuIds(Set.of(1001L))).willReturn(List.of());

            assertFailsWith(() -> processor.reserve(command(item(1001L, 1))), ProductErrorCode.STOCK_INSUFFICIENT, "[1001]");
            verify(stockReservationRepositoryPort, never()).createItems(any());
        }

        @Test
        @DisplayName("부족한 SKU 를 끝까지 돌며 모두 모아(SKU 오름차순) STOCK_INSUFFICIENT 로 실패한다 — 롤백은 Facade 트랜잭션이 한다")
        void collectsAllInsufficient() {
            givenOnSale(1001L, 1002L, 1003L);
            given(stockRepositoryPort.deduct(1001L, 1L)).willReturn(false);
            given(stockRepositoryPort.deduct(1002L, 1L)).willReturn(true);
            given(stockRepositoryPort.deduct(1003L, 1L)).willReturn(false);
            given(stockRepositoryPort.findAllBySkuIds(Set.of(1001L, 1003L)))
                .willReturn(List.of(new Stock(1001L, true, 0L), new Stock(1003L, true, 0L)));

            assertFailsWith(
                () -> processor.reserve(command(item(1003L, 1), item(1002L, 1), item(1001L, 1))),
                ProductErrorCode.STOCK_INSUFFICIENT, "재고가 부족한 SKU 가 있습니다: [1001, 1003]"
            );
            verify(stockRepositoryPort).deduct(1001L, 1L);
            verify(stockRepositoryPort).deduct(1002L, 1L);
            verify(stockRepositoryPort).deduct(1003L, 1L);
            verify(stockReservationRepositoryPort, never()).createItems(any());
        }

        @Test
        @DisplayName("없는 SKU · 숨긴 상품 · 판매 종료 상품 · 판매 종료 SKU 를 모두 모아 STOCK_SKU_NOT_PURCHASABLE — 저장 · 차감하지 않는다")
        void notPurchasable() {
            given(skuRepositoryPort.findAllByIds(List.of(1001L, 1002L, 1003L, 1004L, 1005L, 1006L))).willReturn(List.of(
                sku(1001L, PRODUCT_ID, "", 0L, SaleStatus.ON_SALE),
                sku(1003L, 502L, "", 0L, SaleStatus.ON_SALE),
                sku(1004L, 503L, "", 0L, SaleStatus.ON_SALE),
                sku(1005L, PRODUCT_ID, "", 0L, SaleStatus.STOP_SELLING),
                sku(1006L, 504L, "", 0L, SaleStatus.ON_SALE)
            ));
            given(productRepositoryPort.findAllByIdsIncludingHidden(List.of(PRODUCT_ID, 502L, 503L, 504L))).willReturn(List.of(
                product(PRODUCT_ID),
                product(502L, SaleStatus.ON_SALE, false, false),
                product(503L, SaleStatus.STOP_SELLING, true, false)
            ));

            assertFailsWith(
                () -> processor.reserve(command(item(1001L, 1), item(1002L, 1), item(1003L, 1), item(1004L, 1), item(1005L, 1), item(1006L, 1))),
                ProductErrorCode.STOCK_SKU_NOT_PURCHASABLE, "[1002, 1003, 1004, 1005, 1006]"
            );
            verify(stockReservationRepositoryPort, never()).createHeader(anyLong(), anyLong(), any());
            verify(stockRepositoryPort, never()).deduct(anyLong(), anyLong());
        }

        @Test
        @DisplayName("같은 주문의 선점 생성이 동시에 겹치면 헤더 INSERT 의 409 를 그대로 올리고 차감하지 않는다")
        void concurrentCreate() {
            givenOnSale(1001L);
            doThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT))
                .when(stockReservationRepositoryPort).createHeader(anyLong(), anyLong(), any());

            assertFailsWith(() -> processor.reserve(command(item(1001L, 1))), ProductErrorCode.STOCK_RESERVATION_CONFLICT, "");
            verify(stockRepositoryPort, never()).deduct(anyLong(), anyLong());
            verify(stockReservationRepositoryPort, never()).createItems(any());
        }
    }

    @Nested
    @DisplayName("같은 주문의 활성 선점이 있을 때")
    class ActiveReservation {

        @Test
        @DisplayName("항목이 같으면(중복 합산 후) 기존 선점을 그대로 돌려준다 — 판매 확인 · 저장 · 차감을 하지 않는다")
        void sameItemsReturnsExisting() {
            StockReservation existing = reservation(StockReservationStatus.RESERVED, item(1001L, 3, true), item(1002L, 1, false));
            given(stockReservationRepositoryPort.findActiveByOrderId(ORDER_ID)).willReturn(Optional.of(existing));

            StockReservationInfo info = processor.reserve(command(item(1002L, 1), item(1001L, 1), item(1001L, 2)));

            assertThat(info.reservationId()).isEqualTo(RESERVATION_ID);
            assertThat(info.items()).extracting(StockReservationInfo.Item::quantity).containsExactly(3, 1);
            verify(stockReservationRepositoryPort, never()).createHeader(anyLong(), anyLong(), any());
            verifyNoInteractions(stockRepositoryPort, skuRepositoryPort, productRepositoryPort);
        }

        @Test
        @DisplayName("확정된 선점이어도 항목이 같으면 그대로 돌려준다")
        void confirmedSameItems() {
            given(stockReservationRepositoryPort.findActiveByOrderId(ORDER_ID))
                .willReturn(Optional.of(reservation(StockReservationStatus.CONFIRMED, item(1001L, 1, true))));

            assertThat(processor.reserve(command(item(1001L, 1))).status()).isEqualTo(StockReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("항목이 다르면 409 STOCK_RESERVATION_ITEMS_MISMATCH")
        void differentItems() {
            given(stockReservationRepositoryPort.findActiveByOrderId(ORDER_ID))
                .willReturn(Optional.of(reservation(StockReservationStatus.RESERVED, item(1001L, 1, true))));

            assertFailsWith(() -> processor.reserve(command(item(1001L, 2))), ProductErrorCode.STOCK_RESERVATION_ITEMS_MISMATCH, "");
            verifyNoInteractions(stockRepositoryPort);
        }
    }

    @Nested
    @DisplayName("확정")
    class Confirm {

        @Test
        @DisplayName("RESERVED → CONFIRMED 로 저장한다. 재고는 건드리지 않는다")
        void confirms() {
            givenReservation(StockReservationStatus.RESERVED);

            StockReservationInfo info = processor.confirm(RESERVATION_ID);

            assertThat(info.status()).isEqualTo(StockReservationStatus.CONFIRMED);
            assertThat(updatedReservation().confirmedAt()).isEqualTo(NOW);
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("CONFIRMED 면 저장 없이 200(멱등)")
        void alreadyConfirmed() {
            givenReservation(StockReservationStatus.CONFIRMED);

            assertThat(processor.confirm(RESERVATION_ID).status()).isEqualTo(StockReservationStatus.CONFIRMED);
            verify(stockReservationRepositoryPort, never()).update(any());
        }

        @Test
        @DisplayName("RELEASED 면 도메인 전이 거절을 409 STOCK_RESERVATION_RELEASED 로 바꾼다 — 500 으로 새지 않는다")
        void released() {
            givenReservation(StockReservationStatus.RELEASED);

            assertFailsWith(() -> processor.confirm(RESERVATION_ID), ProductErrorCode.STOCK_RESERVATION_RELEASED, "");
            assertThatThrownBy(() -> processor.confirm(RESERVATION_ID)).hasCauseInstanceOf(StockReservationTransitionException.class);
            verify(stockReservationRepositoryPort, never()).update(any());
        }

        @Test
        @DisplayName("없으면 404 NOT_FOUND_STOCK_RESERVATION")
        void notFound() {
            given(stockReservationRepositoryPort.findById(RESERVATION_ID)).willReturn(Optional.empty());

            assertFailsWith(() -> processor.confirm(RESERVATION_ID), ProductErrorCode.NOT_FOUND_STOCK_RESERVATION, "");
        }

        @Test
        @DisplayName("버전 충돌(409)은 그대로 올린다")
        void versionConflict() {
            givenReservation(StockReservationStatus.RESERVED);
            doThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT)).when(stockReservationRepositoryPort).update(any());

            assertFailsWith(() -> processor.confirm(RESERVATION_ID), ProductErrorCode.STOCK_RESERVATION_CONFLICT, "");
        }
    }

    @Nested
    @DisplayName("해제")
    class Release {

        @Test
        @DisplayName("RESERVED → RELEASED(RELEASED_BY_CALLER) 를 먼저 저장하고, 차감한 항목만 SKU 오름차순으로 되돌린다")
        void releases() {
            givenReservation(StockReservationStatus.RESERVED, item(1001L, 2, true), item(1002L, 1, false), item(1003L, 4, true));
            given(stockRepositoryPort.restore(anyLong(), anyLong())).willReturn(true);

            StockReservationInfo info = processor.release(RESERVATION_ID);

            assertThat(info.status()).isEqualTo(StockReservationStatus.RELEASED);
            StockReservation updated = updatedReservation();
            assertThat(updated.releaseReason()).isEqualTo(StockReleaseReason.RELEASED_BY_CALLER);
            assertThat(updated.releasedAt()).isEqualTo(NOW);
            InOrder order = inOrder(stockReservationRepositoryPort, stockRepositoryPort);
            order.verify(stockReservationRepositoryPort).update(any());
            order.verify(stockRepositoryPort).restore(1001L, 2L);
            order.verify(stockRepositoryPort).restore(1003L, 4L);
            verify(stockRepositoryPort, never()).restore(Mockito.eq(1002L), anyLong());
        }

        @Test
        @DisplayName("RELEASED 면 저장 · 복원 없이 200(멱등) — 같은 해제가 두 번 와도 재고가 늘지 않는다")
        void alreadyReleased() {
            givenReservation(StockReservationStatus.RELEASED);

            assertThat(processor.release(RESERVATION_ID).status()).isEqualTo(StockReservationStatus.RELEASED);
            verify(stockReservationRepositoryPort, never()).update(any());
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("CONFIRMED 면 도메인 전이 거절을 409 STOCK_RESERVATION_CONFIRMED 로 바꾸고 복원하지 않는다 — 500 으로 새지 않는다")
        void confirmed() {
            givenReservation(StockReservationStatus.CONFIRMED);

            assertFailsWith(() -> processor.release(RESERVATION_ID), ProductErrorCode.STOCK_RESERVATION_CONFIRMED, "");
            assertThatThrownBy(() -> processor.release(RESERVATION_ID)).hasCauseInstanceOf(StockReservationTransitionException.class);
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("없으면 404 NOT_FOUND_STOCK_RESERVATION")
        void notFound() {
            given(stockReservationRepositoryPort.findById(RESERVATION_ID)).willReturn(Optional.empty());

            assertFailsWith(() -> processor.release(RESERVATION_ID), ProductErrorCode.NOT_FOUND_STOCK_RESERVATION, "");
        }

        @Test
        @DisplayName("버전 충돌(409)이면 복원하지 않는다 — 이긴 쪽만 재고를 되돌린다")
        void versionConflictDoesNotRestore() {
            givenReservation(StockReservationStatus.RESERVED);
            doThrow(new ProductException(ProductErrorCode.STOCK_RESERVATION_CONFLICT)).when(stockReservationRepositoryPort).update(any());

            assertFailsWith(() -> processor.release(RESERVATION_ID), ProductErrorCode.STOCK_RESERVATION_CONFLICT, "");
            verifyNoInteractions(stockRepositoryPort);
        }
    }

    @Nested
    @DisplayName("만료")
    class Expire {

        @Test
        @DisplayName("RESERVED 이고 만료 시각이 지났으면 RELEASED(EXPIRED) 로 저장하고 차감분을 되돌린다")
        void expires() {
            givenReservation(StockReservationStatus.RESERVED, NOW.minusSeconds(1), item(1001L, 2, true));
            given(stockRepositoryPort.restore(1001L, 2L)).willReturn(true);

            assertThat(processor.expire(RESERVATION_ID)).isTrue();
            assertThat(updatedReservation().releaseReason()).isEqualTo(StockReleaseReason.EXPIRED);
            verify(stockRepositoryPort).restore(1001L, 2L);
        }

        @Test
        @DisplayName("만료 시각 전이면 아무것도 하지 않는다")
        void notYetExpired() {
            givenReservation(StockReservationStatus.RESERVED, NOW.plusSeconds(1), item(1001L, 2, true));

            assertThat(processor.expire(RESERVATION_ID)).isFalse();
            verify(stockReservationRepositoryPort, never()).update(any());
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("목록을 읽은 뒤 확정 · 해제됐거나 없어졌으면 아무것도 하지 않는다 (예외 없이 false)")
        void settledOrMissing() {
            givenReservation(StockReservationStatus.CONFIRMED, NOW.minusSeconds(1), item(1001L, 2, true));
            assertThat(processor.expire(RESERVATION_ID)).isFalse();

            given(stockReservationRepositoryPort.findById(RESERVATION_ID)).willReturn(Optional.empty());
            assertThat(processor.expire(RESERVATION_ID)).isFalse();

            verify(stockReservationRepositoryPort, never()).update(any());
            verifyNoInteractions(stockRepositoryPort);
        }

        @Test
        @DisplayName("만료 대상 조회는 지금 시각으로 하고, 커서가 없으면 처음부터 읽는다")
        void findExpired() {
            given(stockReservationRepositoryPort.findExpiredReservations(NOW, null, 100))
                .willReturn(List.of(new ExpiredStockReservationQueryResult(1L, NOW.minusSeconds(30)), new ExpiredStockReservationQueryResult(2L, NOW)));

            assertThat(processor.findExpiredReservations(null, 100))
                .containsExactly(new ExpiredStockReservationInfo(1L, NOW.minusSeconds(30)), new ExpiredStockReservationInfo(2L, NOW));
        }

        @Test
        @DisplayName("커서를 넘기면 그 건의 (만료 시각, 아이디) 다음부터 읽는다")
        void findExpiredAfterCursor() {
            ExpiredStockReservationInfo cursor = new ExpiredStockReservationInfo(2L, NOW.minusSeconds(10));
            given(stockReservationRepositoryPort.findExpiredReservations(NOW, new ExpiredStockReservationQueryResult(2L, NOW.minusSeconds(10)), 100))
                .willReturn(List.of(new ExpiredStockReservationQueryResult(3L, NOW)));

            assertThat(processor.findExpiredReservations(cursor, 100)).containsExactly(new ExpiredStockReservationInfo(3L, NOW));
        }
    }

    private void givenOnSale(long... skuIds) {
        List<Long> sortedIds = Arrays.stream(skuIds).sorted().boxed().toList();
        given(skuRepositoryPort.findAllByIds(sortedIds))
            .willReturn(sortedIds.stream().map(skuId -> sku(skuId, PRODUCT_ID, "", 0L, SaleStatus.ON_SALE)).toList());
        given(productRepositoryPort.findAllByIdsIncludingHidden(List.of(PRODUCT_ID))).willReturn(List.of(product(PRODUCT_ID)));
    }

    private void givenReservation(StockReservationStatus status, StockReservationItem... items) {
        givenReservation(status, NOW.plus(TTL), items.length == 0 ? new StockReservationItem[] {item(1001L, 1, true)} : items);
    }

    private void givenReservation(StockReservationStatus status, Instant expiresAt, StockReservationItem... items) {
        given(stockReservationRepositoryPort.findById(RESERVATION_ID)).willReturn(Optional.of(reservation(status, expiresAt, items)));
    }

    private StockReservation savedReservation() {
        ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
        verify(stockReservationRepositoryPort).createItems(captor.capture());
        return captor.getValue();
    }

    private StockReservation updatedReservation() {
        ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
        verify(stockReservationRepositoryPort).update(captor.capture());
        return captor.getValue();
    }

    private static StockReservation reservation(StockReservationStatus status, StockReservationItem... items) {
        return reservation(status, NOW.plus(TTL), items);
    }

    private static StockReservation reservation(StockReservationStatus status, Instant expiresAt, StockReservationItem... items) {
        Instant confirmedAt = status == StockReservationStatus.CONFIRMED ? NOW.minusSeconds(60) : null;
        Instant releasedAt = status == StockReservationStatus.RELEASED ? NOW.minusSeconds(60) : null;
        StockReleaseReason reason = status == StockReservationStatus.RELEASED ? StockReleaseReason.RELEASED_BY_CALLER : null;
        return new StockReservation(RESERVATION_ID, ORDER_ID, status, expiresAt, confirmedAt, releasedAt, reason, List.of(items), 0L);
    }

    private static StockReservationItem item(long skuId, int quantity, boolean deducted) {
        return new StockReservationItem(skuId + 50_000L, skuId, quantity, deducted);
    }

    private static StockReservationCommand.Item item(long skuId, int quantity) {
        return new StockReservationCommand.Item(skuId, quantity);
    }

    private static StockReservationCommand command(StockReservationCommand.Item... items) {
        return new StockReservationCommand(ORDER_ID, List.of(items));
    }

    private static void assertFailsWith(Runnable call, ProductErrorCode errorCode, String messagePart) {
        assertThatThrownBy(call::run)
            .isInstanceOf(ProductException.class)
            .hasMessageContaining(messagePart)
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
