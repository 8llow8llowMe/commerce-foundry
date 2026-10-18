package com.commercefoundry.domainlayer.cart.adapter.in.web.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartLineItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartMergeResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartResponse;
import com.commercefoundry.domainlayer.cart.application.info.CartInfo;
import com.commercefoundry.domainlayer.cart.application.info.CartLineInfo;
import com.commercefoundry.domainlayer.cart.application.info.CartMergeInfo;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkip;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkipReason;
import com.commercefoundry.domainlayer.cart.domain.model.CartItemStatus;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CartWebPresenterTest {

    private final CartWebPresenter presenter = new CartWebPresenter();

    private static final CartLineInfo ON_SALE_LINE = CartLineInfo.builder()
        .itemId(4611686018427387999L).skuId(9004L).productId(7301L).productName("저고리").optionName("아이보리 / L").quantity(2)
        .unitPrice(Money.of(8_950, CurrencyCode.USD)).lineAmount(Money.of(17_900, CurrencyCode.USD)).status(CartItemStatus.ON_SALE)
        .maxQuantityPerOrder(10).customOrder(true).customOptions(List.of(new CustomOption(1L, "chest", "96"))).quantityExceeded(false)
        .build();

    private static final CartLineInfo MISSING_SKU_LINE = CartLineInfo.builder()
        .itemId(4611686018427388000L).skuId(9005L).quantity(1).status(CartItemStatus.STOP_SELLING).customOptions(List.of())
        .build();

    @Test
    @DisplayName("아이디는 문자열(19자리 그대로), 금액은 최소 단위 + 통화, 상태는 metadata 다")
    void line() {
        CartResponse response = presenter.toResponse(new CartInfo(List.of(ON_SALE_LINE), Money.of(17_900, CurrencyCode.USD), 1));

        CartLineItem line = response.items().getFirst();
        assertThat(line.itemId()).isEqualTo("4611686018427387999");
        assertThat(line.skuId()).isEqualTo("9004");
        assertThat(line.productId()).isEqualTo("7301");
        assertThat(line.unitPrice()).isEqualTo(MoneyItem.of(8_950, "USD"));
        assertThat(line.lineAmount()).isEqualTo(MoneyItem.of(17_900, "USD"));
        assertThat(line.status().code()).isEqualTo("ON_SALE");
        assertThat(line.status().name()).isEqualTo("구매 가능");
        assertThat(line.customOptions()).singleElement().satisfies(option -> {
            assertThat(option.fieldKey()).isEqualTo("chest");
            assertThat(option.value()).isEqualTo("96");
        });
        assertThat(response.totalAmount()).isEqualTo(MoneyItem.of(17_900, "USD"));
        assertThat(response.purchasableCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SKU 를 찾지 못한 줄은 상품 아이디 · 금액이 null 이고, 구매 가능 줄이 없으면 합계도 null 이다")
    void nullsStayNull() {
        CartResponse response = presenter.toResponse(new CartInfo(List.of(MISSING_SKU_LINE), null, 0));

        CartLineItem line = response.items().getFirst();
        assertThat(line.productId()).isNull();
        assertThat(line.unitPrice()).isNull();
        assertThat(line.lineAmount()).isNull();
        assertThat(line.status().code()).isEqualTo("STOP_SELLING");
        assertThat(response.totalAmount()).isNull();
    }

    @Test
    @DisplayName("병합 결과의 건너뛴 항목 — 위치는 그대로, SKU 아이디는 문자열(19자리 그대로), 사유는 enum 이름")
    void merge() {
        CartMergeResponse response = presenter.toMergeResponse(new CartMergeInfo(new CartInfo(List.of(), null, 0), List.of(
            new CartMergeSkip(0, 4611686018427387904L, CartMergeSkipReason.SKU_NOT_FOUND),
            new CartMergeSkip(3, 9005L, CartMergeSkipReason.LINE_LIMIT_EXCEEDED))));

        assertThat(response.skippedItems()).extracting(item -> item.index() + ":" + item.skuId() + ":" + item.reason())
            .containsExactly("0:4611686018427387904:SKU_NOT_FOUND", "3:9005:LINE_LIMIT_EXCEEDED");
        assertThat(response.cart().items()).isEmpty();
    }
}
