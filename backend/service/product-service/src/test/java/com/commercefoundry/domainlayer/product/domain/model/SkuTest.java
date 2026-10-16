package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkuTest {

    @Test
    @DisplayName("isOnSale 은 SKU 의 판매 상태만 본다")
    void isOnSale() {
        assertThat(ProductFixtures.sku(1, 7, "", 0, SaleStatus.ON_SALE).isOnSale()).isTrue();
        assertThat(ProductFixtures.sku(2, 7, "", 0, SaleStatus.STOP_SELLING).isOnSale()).isFalse();
    }
}
