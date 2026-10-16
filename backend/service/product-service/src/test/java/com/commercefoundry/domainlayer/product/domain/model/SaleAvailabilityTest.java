package com.commercefoundry.domainlayer.product.domain.model;

import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.ON_SALE;
import static com.commercefoundry.domainlayer.product.domain.model.SaleStatus.STOP_SELLING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class SaleAvailabilityTest {

    private static final StockSnapshot UNLIMITED = StockSnapshot.unlimited();
    private static final StockSnapshot IN_STOCK = new StockSnapshot(true, 5);
    private static final StockSnapshot EMPTY = new StockSnapshot(true, 0);

    static Stream<Arguments> skuCases() {
        return Stream.of(
            Arguments.of(ON_SALE, ON_SALE, UNLIMITED, SaleAvailability.ON_SALE),
            Arguments.of(ON_SALE, ON_SALE, IN_STOCK, SaleAvailability.ON_SALE),
            Arguments.of(ON_SALE, ON_SALE, EMPTY, SaleAvailability.SOLD_OUT),
            Arguments.of(STOP_SELLING, ON_SALE, UNLIMITED, SaleAvailability.STOP_SELLING),
            Arguments.of(ON_SALE, STOP_SELLING, UNLIMITED, SaleAvailability.STOP_SELLING),
            Arguments.of(STOP_SELLING, STOP_SELLING, IN_STOCK, SaleAvailability.STOP_SELLING),
            // 판매 종료가 품절보다 앞선다
            Arguments.of(STOP_SELLING, ON_SALE, EMPTY, SaleAvailability.STOP_SELLING),
            Arguments.of(ON_SALE, STOP_SELLING, EMPTY, SaleAvailability.STOP_SELLING)
        );
    }

    @ParameterizedTest(name = "상품 {0} · SKU {1} · 재고 {2} → {3}")
    @MethodSource("skuCases")
    @DisplayName("SKU 판정: 판매 종료 → 품절 → 판매중 순으로 본다")
    void ofSku(SaleStatus productStatus, SaleStatus skuStatus, StockSnapshot stock, SaleAvailability expected) {
        assertThat(SaleAvailability.ofSku(productStatus, skuStatus, stock)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "상품 {0} · 판매 가능 SKU {1} → {2}")
    @CsvSource({
        "ON_SALE, true, ON_SALE",
        "ON_SALE, false, SOLD_OUT",
        "STOP_SELLING, true, STOP_SELLING",
        "STOP_SELLING, false, STOP_SELLING"
    })
    @DisplayName("상품 판정: 판매 종료가 아니면 살 수 있는 SKU 유무로 판매중 / 품절이다 (SKU 0건은 품절)")
    void ofProduct(SaleStatus productStatus, boolean hasSellableSku, SaleAvailability expected) {
        assertThat(SaleAvailability.ofProduct(productStatus, hasSellableSku)).isEqualTo(expected);
    }

    @Test
    @DisplayName("응답 metadata 는 {code, name, description} 이다")
    void metadata() {
        assertThat(SaleAvailability.SOLD_OUT.toMetadata())
            .isEqualTo(CodeNameDescriptionMetadata.of("SOLD_OUT", "품절", "재고가 모두 소진되었습니다."));
        assertThat(SaleAvailability.STOP_SELLING.toMetadata().name()).isEqualTo("판매 종료");
    }

    @Test
    @DisplayName("재고 상태: 무제한은 품절이 아니고, 관리 중 수량 0 만 품절이다")
    void stockSnapshot() {
        assertThat(UNLIMITED.isSoldOut()).isFalse();
        assertThat(IN_STOCK.isSoldOut()).isFalse();
        assertThat(EMPTY.isSoldOut()).isTrue();
        assertThat(new StockSnapshot(false, 0).isSoldOut()).isFalse();
        assertThatThrownBy(() -> new StockSnapshot(true, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
