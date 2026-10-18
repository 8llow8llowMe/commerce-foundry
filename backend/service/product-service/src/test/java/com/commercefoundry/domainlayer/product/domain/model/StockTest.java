package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StockTest {

    @Nested
    @DisplayName("StockSnapshot")
    class Snapshot {

        @Test
        @DisplayName("재고 행 없음(empty)은 재고 관리 · 수량 0 이라 품절이다")
        void emptyIsSoldOut() {
            assertThat(StockSnapshot.empty()).isEqualTo(new StockSnapshot(true, 0));
            assertThat(StockSnapshot.empty().isSoldOut()).isTrue();
        }

        @Test
        @DisplayName("무제한(unlimited)은 수량 0 이어도 품절이 아니다")
        void unlimitedIsNotSoldOut() {
            assertThat(StockSnapshot.unlimited().stockManaged()).isFalse();
            assertThat(StockSnapshot.unlimited().isSoldOut()).isFalse();
        }

        @Test
        @DisplayName("재고 관리 중이면 수량 0 만 품절이다")
        void managedSoldOutOnlyAtZero() {
            assertThat(new StockSnapshot(true, 0).isSoldOut()).isTrue();
            assertThat(new StockSnapshot(true, 1).isSoldOut()).isFalse();
        }

        @Test
        @DisplayName("음수 수량은 만들 수 없다")
        void negativeQuantityIsRejected() {
            assertThatThrownBy(() -> new StockSnapshot(true, -1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Stock")
    class StockRow {

        @Test
        @DisplayName("재고 행을 판정용 상태로 바꾼다")
        void toSnapshot() {
            assertThat(new Stock(1, true, 3).toSnapshot()).isEqualTo(new StockSnapshot(true, 3));
            assertThat(new Stock(1, false, 0).toSnapshot()).isEqualTo(StockSnapshot.unlimited());
        }

        @Test
        @DisplayName("음수 수량은 만들 수 없다")
        void negativeQuantityIsRejected() {
            assertThatThrownBy(() -> new Stock(1, true, -1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("SkuStocks")
    class Stocks {

        @Test
        @DisplayName("SKU 아이디로 재고를 찾고, 재고 행이 없는 SKU 는 품절(empty)이다")
        void missingRowIsSoldOut() {
            SkuStocks stocks = SkuStocks.of(List.of(new Stock(1, true, 5), new Stock(2, false, 0)));

            assertThat(stocks.snapshotOf(1)).isEqualTo(new StockSnapshot(true, 5));
            assertThat(stocks.snapshotOf(2)).isEqualTo(StockSnapshot.unlimited());
            assertThat(stocks.snapshotOf(3)).isEqualTo(StockSnapshot.empty());
            assertThat(stocks.snapshotOf(3).isSoldOut()).isTrue();
        }

        @Test
        @DisplayName("재고 행이 하나도 없으면 모든 SKU 가 품절이다")
        void noRows() {
            assertThat(SkuStocks.of(List.of()).snapshotOf(1).isSoldOut()).isTrue();
        }
    }
}
