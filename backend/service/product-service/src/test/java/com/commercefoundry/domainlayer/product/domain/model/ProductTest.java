package com.commercefoundry.domainlayer.product.domain.model;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static final LocalDate SHIP_DATE = LocalDate.of(2026, 11, 20);

    @Test
    @DisplayName("프리오더 상품은 발송 예정일을 가질 수 있다")
    void preorderWithShipDate() {
        Product product = product(true, SHIP_DATE);

        assertThat(product.preorder()).isTrue();
        assertThat(product.expectedShipDate()).isEqualTo(SHIP_DATE);
    }

    @Test
    @DisplayName("프리오더 상품의 발송 예정일이 없으면 '발송일 추후 안내' 다 — 만들 수 있다")
    void preorderWithoutShipDate() {
        assertThat(product(true, null).expectedShipDate()).isNull();
    }

    @Test
    @DisplayName("일반 상품은 발송 예정일이 없다")
    void regularWithoutShipDate() {
        Product product = product(false, null);

        assertThat(product.preorder()).isFalse();
        assertThat(product.expectedShipDate()).isNull();
    }

    @Test
    @DisplayName("프리오더가 아닌데 발송 예정일이 있으면 거절한다 — DB check ck_product_expected_ship_date_preorder 와 같다")
    void regularWithShipDateRejected() {
        assertThatThrownBy(() -> product(false, SHIP_DATE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expectedShipDate");
    }

    private static Product product(boolean preorder, LocalDate expectedShipDate) {
        return new Product(
            1L, 11L, "상품", null, null, SaleStatus.ON_SALE, true, Money.of(39_000, KRW), null, null,
            false, null, null, preorder, expectedShipDate, 0L, null, null
        );
    }
}
