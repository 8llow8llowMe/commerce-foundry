package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkuTest {

    @Test
    @DisplayName("isOnSale 은 SKU 의 판매 상태만 본다")
    void isOnSale() {
        assertThat(ProductFixtures.sku(1, 7, "", 0, SaleStatus.ON_SALE).isOnSale()).isTrue();
        assertThat(ProductFixtures.sku(2, 7, "", 0, SaleStatus.STOP_SELLING).isOnSale()).isFalse();
    }

    @Test
    @DisplayName("무게 · HS 코드는 없어도 된다 — 해외 판매 상품만 필수이고, 거절은 주문 쪽이 한다")
    void weightAndHsCodeAreOptional() {
        Sku sku = ProductFixtures.sku(1, 7, "", 0, SaleStatus.ON_SALE, null, null);

        assertThat(sku.weightGrams()).isNull();
        assertThat(sku.hsCode()).isNull();
    }

    @Test
    @DisplayName("무게 1g · HS 코드를 싣는다")
    void weightAndHsCode() {
        Sku sku = ProductFixtures.sku(1, 7, "", 0, SaleStatus.ON_SALE, 1, "621142");

        assertThat(sku.weightGrams()).isEqualTo(1);
        assertThat(sku.hsCode()).isEqualTo(new HsCode("621142"));
    }

    @ParameterizedTest(name = "{0}g")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("무게가 있으면 1g 이상이어야 한다")
    void nonPositiveWeightIsRejected(int weightGrams) {
        assertThatThrownBy(() -> ProductFixtures.sku(1, 7, "", 0, SaleStatus.ON_SALE, weightGrams, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(String.valueOf(weightGrams));
    }
}
