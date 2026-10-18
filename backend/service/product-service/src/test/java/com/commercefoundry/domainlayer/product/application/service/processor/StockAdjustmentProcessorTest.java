package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.command.StockAdjustmentCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.StockAdminInfo;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockAdjustmentProcessorTest {

    private static final long SKU_ID = 1001L;
    private static final long ADMIN_ID = 4611686018427387904L;
    private static final long HISTORY_ID = 777L;
    private static final Instant UPDATED_AT = Instant.parse("2026-10-18T09:00:00Z");

    @Mock
    private SkuRepositoryPort skuRepositoryPort;

    @Mock
    private StockRepositoryPort stockRepositoryPort;

    @Mock
    private StockAdjustmentHistoryRepositoryPort stockAdjustmentHistoryRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @InjectMocks
    private StockAdjustmentProcessor processor;

    @Test
    @DisplayName("없는 SKU 면 404 · NOT_FOUND_SKU 이고 재고 · 이력을 건드리지 않는다")
    void skuNotFound() {
        given(skuRepositoryPort.existsById(SKU_ID)).willReturn(false);

        assertFailsWith(command(5L, 0L, null), ProductErrorCode.NOT_FOUND_SKU);
        verifyNoInteractions(stockRepositoryPort, stockAdjustmentHistoryRepositoryPort);
    }

    @Nested
    @DisplayName("재고 행이 없는 SKU (품절 — 관리 true · 수량 0 으로 본다)")
    class FirstRegistration {

        @BeforeEach
        void setUp() {
            given(skuRepositoryPort.existsById(SKU_ID)).willReturn(true);
        }

        @Test
        @DisplayName("수량을 보내면 관리 true 로 INSERT 하고, 이력의 조정 전 값은 관리 true · 수량 0 이다")
        void createsWithQuantity() {
            givenRows(null, row(true, 12L));
            given(snowflakeIdGenerator.generateId()).willReturn(HISTORY_ID);

            StockAdminInfo info = processor.adjust(command(12L, 0L, null));

            verify(stockRepositoryPort).create(new Stock(SKU_ID, true, 12L));
            verify(stockRepositoryPort, never()).updateQuantityIfMatches(anyLong(), anyLong(), anyLong());
            assertThat(info).isEqualTo(new StockAdminInfo(SKU_ID, true, 12L, UPDATED_AT));
            assertThat(appendedHistory()).isEqualTo(new StockAdjustmentHistory(
                HISTORY_ID, SKU_ID, 0L, 12L, 12L, true, true, StockAdjustmentReason.RESTOCK, "10월 입고분", ADMIN_ID
            ));
        }

        @Test
        @DisplayName("관리 여부만 보내면 수량 0 으로 INSERT 한다 — 무제한으로 등록")
        void createsUnlimited() {
            givenRows(null, row(false, 0L));
            given(snowflakeIdGenerator.generateId()).willReturn(HISTORY_ID);

            processor.adjust(command(null, null, false));

            verify(stockRepositoryPort).create(new Stock(SKU_ID, false, 0L));
            StockAdjustmentHistory history = appendedHistory();
            assertThat(history.beforeStockManaged()).isTrue();
            assertThat(history.afterStockManaged()).isFalse();
            assertThat(history.delta()).isZero();
        }

        @Test
        @DisplayName("기대 수량이 0 이 아니면 409 · STOCK_ADJUSTMENT_CONFLICT 이고 INSERT 하지 않는다")
        void expectedQuantityMustBeZero() {
            given(stockRepositoryPort.findBySkuId(SKU_ID)).willReturn(Optional.empty());

            assertFailsWith(command(12L, 3L, null), ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
            verify(stockRepositoryPort, never()).create(any());
            verifyNoInteractions(stockAdjustmentHistoryRepositoryPort);
        }

        @Test
        @DisplayName("동시에 다른 어드민이 먼저 등록했으면(어댑터의 PK 충돌 409) 그대로 전파하고 이력을 남기지 않는다")
        void concurrentCreate() {
            given(stockRepositoryPort.findBySkuId(SKU_ID)).willReturn(Optional.empty());
            doThrow(new ProductException(ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT)).when(stockRepositoryPort).create(any());

            assertFailsWith(command(12L, 0L, null), ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
            verifyNoInteractions(stockAdjustmentHistoryRepositoryPort);
        }
    }

    @Nested
    @DisplayName("재고 행이 있는 SKU")
    class ExistingRow {

        @BeforeEach
        void setUp() {
            given(skuRepositoryPort.existsById(SKU_ID)).willReturn(true);
        }

        @Test
        @DisplayName("수량 CAS 가 성공하면 조정 전 수량은 기대 수량이다 — 처음 읽은 값이 아니다")
        void casSucceeds() {
            // 처음 읽을 때는 7 이었지만 그 사이 주문 차감으로 5 가 됐고, 어드민은 5 를 보고 조정했다
            givenRows(row(true, 7L), row(true, 12L));
            given(stockRepositoryPort.updateQuantityIfMatches(SKU_ID, 5L, 12L)).willReturn(true);
            given(snowflakeIdGenerator.generateId()).willReturn(HISTORY_ID);

            StockAdminInfo info = processor.adjust(command(12L, 5L, null));

            assertThat(info).isEqualTo(new StockAdminInfo(SKU_ID, true, 12L, UPDATED_AT));
            verify(stockRepositoryPort, never()).updateStockManaged(anyLong(), anyBoolean());
            verify(stockRepositoryPort, never()).create(any());
            StockAdjustmentHistory history = appendedHistory();
            assertThat(history.beforeQuantity()).isEqualTo(5L);
            assertThat(history.afterQuantity()).isEqualTo(12L);
            assertThat(history.delta()).isEqualTo(7L);
            assertThat(history.adminMemberId()).isEqualTo(ADMIN_ID);
        }

        @Test
        @DisplayName("CAS 가 0행이면 409 · STOCK_ADJUSTMENT_CONFLICT 이고 관리 여부 · 이력을 건드리지 않는다")
        void casFails() {
            given(stockRepositoryPort.findBySkuId(SKU_ID)).willReturn(Optional.of(row(true, 4L)));
            given(stockRepositoryPort.updateQuantityIfMatches(SKU_ID, 5L, 12L)).willReturn(false);

            assertFailsWith(command(12L, 5L, true), ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
            verify(stockRepositoryPort, never()).updateStockManaged(anyLong(), anyBoolean());
            verifyNoInteractions(stockAdjustmentHistoryRepositoryPort);
        }

        @Test
        @DisplayName("관리 여부만 바꾸면 수량은 건드리지 않고, 이력의 수량 변화는 0 이다 (다시 읽은 수량을 조정 전 · 후에 쓴다)")
        void toggleOnly() {
            // 처음 읽을 때 5, 관리 여부를 바꾸기 전에 주문 차감으로 4 — 그 차감을 어드민 조정으로 기록하지 않는다
            givenRows(row(true, 5L), row(false, 4L));
            given(stockRepositoryPort.updateStockManaged(SKU_ID, false)).willReturn(true);
            given(snowflakeIdGenerator.generateId()).willReturn(HISTORY_ID);

            processor.adjust(StockAdjustmentCommand.builder()
                .skuId(SKU_ID).stockManaged(false).reason(StockAdjustmentReason.MANAGEMENT_CHANGE).adminMemberId(ADMIN_ID).build());

            verify(stockRepositoryPort, never()).updateQuantityIfMatches(anyLong(), anyLong(), anyLong());
            assertThat(appendedHistory()).isEqualTo(new StockAdjustmentHistory(
                HISTORY_ID, SKU_ID, 4L, 4L, 0L, true, false, StockAdjustmentReason.MANAGEMENT_CHANGE, null, ADMIN_ID
            ));
        }

        @Test
        @DisplayName("수량과 관리 여부를 함께 보내면 CAS 다음에 관리 여부를 바꾼다")
        void quantityAndToggle() {
            givenRows(row(false, 0L), row(true, 3L));
            given(stockRepositoryPort.updateQuantityIfMatches(SKU_ID, 0L, 3L)).willReturn(true);
            given(stockRepositoryPort.updateStockManaged(SKU_ID, true)).willReturn(true);
            given(snowflakeIdGenerator.generateId()).willReturn(HISTORY_ID);

            processor.adjust(command(3L, 0L, true));

            InOrder order = inOrder(stockRepositoryPort, stockAdjustmentHistoryRepositoryPort);
            order.verify(stockRepositoryPort).updateQuantityIfMatches(SKU_ID, 0L, 3L);
            order.verify(stockRepositoryPort).updateStockManaged(SKU_ID, true);
            order.verify(stockAdjustmentHistoryRepositoryPort).append(any());
            assertThat(appendedHistory()).isEqualTo(new StockAdjustmentHistory(
                HISTORY_ID, SKU_ID, 0L, 3L, 3L, false, true, StockAdjustmentReason.RESTOCK, "10월 입고분", ADMIN_ID
            ));
        }

        @Test
        @DisplayName("관리 여부 변경이 0행이면(행이 사라짐) 409 · STOCK_ADJUSTMENT_CONFLICT 다")
        void toggleMissingRow() {
            given(stockRepositoryPort.findBySkuId(SKU_ID)).willReturn(Optional.of(row(true, 5L)));
            given(stockRepositoryPort.updateStockManaged(SKU_ID, false)).willReturn(false);

            assertFailsWith(command(null, null, false), ProductErrorCode.STOCK_ADJUSTMENT_CONFLICT);
            verifyNoInteractions(stockAdjustmentHistoryRepositoryPort);
        }
    }

    private void givenRows(StockQueryResult before, StockQueryResult after) {
        given(stockRepositoryPort.findBySkuId(SKU_ID)).willReturn(Optional.ofNullable(before)).willReturn(Optional.of(after));
    }

    private void assertFailsWith(StockAdjustmentCommand command, ProductErrorCode errorCode) {
        assertThatThrownBy(() -> processor.adjust(command))
            .isInstanceOf(ProductException.class)
            .extracting(exception -> ((ProductException) exception).getErrorCode())
            .isEqualTo(errorCode);
    }

    private StockAdjustmentHistory appendedHistory() {
        ArgumentCaptor<StockAdjustmentHistory> captor = ArgumentCaptor.forClass(StockAdjustmentHistory.class);
        verify(stockAdjustmentHistoryRepositoryPort).append(captor.capture());
        return captor.getValue();
    }

    private static StockQueryResult row(boolean stockManaged, long availableQuantity) {
        return new StockQueryResult(SKU_ID, stockManaged, availableQuantity, UPDATED_AT);
    }

    private static StockAdjustmentCommand command(Long quantity, Long expectedQuantity, Boolean stockManaged) {
        return StockAdjustmentCommand.builder()
            .skuId(SKU_ID)
            .quantity(quantity)
            .expectedQuantity(expectedQuantity)
            .stockManaged(stockManaged)
            .reason(StockAdjustmentReason.RESTOCK)
            .memo("10월 입고분")
            .adminMemberId(ADMIN_ID)
            .build();
    }
}
