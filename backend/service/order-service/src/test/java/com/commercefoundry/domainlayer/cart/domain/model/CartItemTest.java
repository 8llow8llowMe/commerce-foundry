package com.commercefoundry.domainlayer.cart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CartItemTest {

    private static final CustomOptions CHEST_96 = CustomOptions.of(List.of(CustomOption.of("chest", "96")));

    private static CartItem saved(int quantity) {
        return new CartItem(1L, 10L, 100L, quantity, CustomOptions.empty(), 3L);
    }

    @ParameterizedTest(name = "maxQuantityPerOrder={0} → {1}")
    @CsvSource(value = {"null, 99", "10, 10", "99, 99", "150, 99", "1, 1"}, nullValues = "null")
    @DisplayName("줄 수량 상한은 99 와 상품의 1회 주문 최대 수량 중 작은 값이다")
    void maxQuantity(Integer maxQuantityPerOrder, int expected) {
        assertThat(CartItem.maxQuantity(maxQuantityPerOrder)).isEqualTo(expected);
    }

    @Test
    @DisplayName("수량은 1 이상이다 — 0 이하 줄은 만들 수 없다")
    void quantityMustBePositive() {
        assertThatThrownBy(() -> new CartItem(1L, 10L, 100L, 0, CustomOptions.empty(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("새 줄")
    class Create {

        @Test
        @DisplayName("버전 없이 만들어진다 — 저장 전이다")
        void createsNew() {
            CartItem item = CartItem.create(1L, 10L, 100L, 5, CHEST_96, 10);

            assertThat(item.isNew()).isTrue();
            assertThat(item.quantity()).isEqualTo(5);
            assertThat(item.customOptions()).isEqualTo(CHEST_96);
        }

        @Test
        @DisplayName("상한을 넘는 수량은 만들 수 없다")
        void rejectsOverMax() {
            assertThatThrownBy(() -> CartItem.create(1L, 10L, 100L, 11, CHEST_96, 10)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("같은 줄 — SKU 와 맞춤 제작 입력이 모두 같다")
    void sameLine() {
        CartItem item = new CartItem(1L, 10L, 100L, 1, CHEST_96, 0L);

        assertThat(item.isSameLine(100L, CustomOptions.of(List.of(CustomOption.of("chest", " 96 "))))).isTrue();
        assertThat(item.isSameLine(100L, CustomOptions.empty())).isFalse();
        assertThat(item.isSameLine(101L, CHEST_96)).isFalse();
    }

    @Nested
    @DisplayName("수량 더하기 (다시 담기)")
    class AddQuantity {

        @Test
        @DisplayName("상한까지는 더한다 — 버전은 그대로 들고 간다")
        void addsUpToMax() {
            CartItem added = saved(7).addQuantity(3, 10);

            assertThat(added.quantity()).isEqualTo(10);
            assertThat(added.version()).isEqualTo(3L);
            assertThat(added.isNew()).isFalse();
        }

        @Test
        @DisplayName("상한을 넘으면 거절한다 — canAdd 가 먼저 알려 준다")
        void rejectsOverMax() {
            assertThat(saved(7).canAdd(4, 10)).isFalse();
            assertThatThrownBy(() -> saved(7).addQuantity(4, 10)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("int 범위를 넘는 합도 넘침 없이 거절한다")
        void noOverflow() {
            assertThat(saved(99).canAdd(Integer.MAX_VALUE, 99)).isFalse();
        }
    }

    @Nested
    @DisplayName("수량 더하되 상한에서 자르기 (병합)")
    class AddQuantityUpTo {

        @Test
        @DisplayName("상한을 넘는 합은 상한으로 자른다")
        void caps() {
            assertThat(saved(8).addQuantityUpTo(5, 10).quantity()).isEqualTo(10);
            assertThat(saved(8).addQuantityUpTo(1, 10).quantity()).isEqualTo(9);
        }

        @Test
        @DisplayName("이미 상한 이상이면 그대로다 — 병합이 수량을 줄이지 않는다")
        void neverDecreases() {
            CartItem over = saved(12);

            assertThat(over.addQuantityUpTo(3, 10)).isSameAs(over);
            assertThat(saved(10).addQuantityUpTo(1, 10).quantity()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("수량 바꾸기")
    class ChangeQuantity {

        @Test
        @DisplayName("1 이상 상한 이하로 바꾼다")
        void changes() {
            assertThat(saved(5).changeQuantity(1, 10).quantity()).isEqualTo(1);
            assertThat(saved(5).changeQuantity(10, 10).quantity()).isEqualTo(10);
        }

        @ParameterizedTest(name = "quantity={0}")
        @CsvSource({"0", "11", "-1"})
        @DisplayName("범위 밖은 거절한다")
        void rejectsOutOfRange(int quantity) {
            assertThat(CartItem.isQuantityAllowed(quantity, 10)).isFalse();
            assertThatThrownBy(() -> saved(5).changeQuantity(quantity, 10)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("상한을 넘는가 — 담은 뒤 1회 주문 최대 수량이 줄어든 경우")
    void exceeds() {
        assertThat(saved(12).exceeds(10)).isTrue();
        assertThat(saved(10).exceeds(10)).isFalse();
    }

    /**
     * product-service 는 숨긴 상품도 판매 상태를 그대로 내리고 {@code purchasable=false} 로만 알린다 — 그래서 숨김 + 품절은 (false, true) 로 오고 품절이다.
     * 숨김 + 판매중은 (false, false) 로 와 판매 중지다.
     */
    @ParameterizedTest(name = "{0} — found={1}, purchasable={2}, soldOut={3} → {4}")
    @CsvSource({
        "SKU 없음,              false, false, false, STOP_SELLING",
        "SKU 없음(값 무시),     false, true,  false, STOP_SELLING",
        "노출 · 판매중,         true,  true,  false, ON_SALE",
        "노출 · 품절,           true,  false, true,  SOLD_OUT",
        "숨김 · 품절(품절 우선), true,  false, true,  SOLD_OUT",
        "숨김 · 판매중,         true,  false, false, STOP_SELLING",
        "판매 종료,             true,  false, false, STOP_SELLING"
    })
    @DisplayName("줄 상태 — SKU 없음 · 판매 종료 · 품절이 아닌 숨김은 판매 중지, 품절은 숨김이어도 품절, 구매 가능은 구매 가능")
    void status(String caseName, boolean skuFound, boolean purchasable, boolean soldOut, CartItemStatus expected) {
        assertThat(CartItemStatus.resolve(skuFound, purchasable, soldOut)).isEqualTo(expected);
    }
}
