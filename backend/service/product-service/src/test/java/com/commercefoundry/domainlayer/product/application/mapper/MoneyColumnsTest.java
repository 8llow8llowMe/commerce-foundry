package com.commercefoundry.domainlayer.product.application.mapper;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyColumnsTest {

    @Test
    @DisplayName("required: 금액 · 통화를 Money 로 묶고, 통화가 없거나 음수면 데이터 오류다")
    void required() {
        assertThat(MoneyColumns.required(39_000L, KRW)).isEqualTo(Money.of(39_000, KRW));
        assertThatThrownBy(() -> MoneyColumns.required(39_000L, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MoneyColumns.required(-1L, KRW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("nullable: 둘 다 없으면 null, 둘 다 있으면 Money, 한쪽만 있으면 데이터 오류다")
    void nullable() {
        assertThat(MoneyColumns.nullable(null, null)).isNull();
        assertThat(MoneyColumns.nullable(45_000L, KRW)).isEqualTo(Money.of(45_000, KRW));
        assertThatThrownBy(() -> MoneyColumns.nullable(45_000L, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MoneyColumns.nullable(null, KRW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("목록 행의 판매가 · 정가도 같은 규칙으로 꺼낸다")
    void listQueryResult() {
        ProductListQueryResult discounted = new ProductListQueryResult(101L, "상품", 39_000L, KRW, 45_000L, KRW, SaleStatus.ON_SALE, false, false);
        ProductListQueryResult regular = new ProductListQueryResult(102L, "상품", 39_000L, KRW, null, null, SaleStatus.ON_SALE, false, false);
        ProductListQueryResult broken = new ProductListQueryResult(103L, "상품", 39_000L, KRW, 45_000L, null, SaleStatus.ON_SALE, false, false);

        assertThat(discounted.salePrice()).isEqualTo(Money.of(39_000, KRW));
        assertThat(discounted.listPrice()).isEqualTo(Money.of(45_000, KRW));
        assertThat(regular.listPrice()).isNull();
        assertThatThrownBy(broken::listPrice).isInstanceOf(IllegalStateException.class);
    }
}
