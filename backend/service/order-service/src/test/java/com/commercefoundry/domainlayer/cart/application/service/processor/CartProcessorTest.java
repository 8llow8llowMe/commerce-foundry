package com.commercefoundry.domainlayer.cart.application.service.processor;

import static com.commercefoundry.domainlayer.cart.support.CartFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.OTHER_MEMBER_ID;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.add;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.cartSku;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.customOrder;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.onSale;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.options;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.soldOut;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.withMaxQuantity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.commercefoundry.domainlayer.cart.application.command.CartItemAddCommand;
import com.commercefoundry.domainlayer.cart.application.command.CartMergeCommand;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkip;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkipReason;
import com.commercefoundry.domainlayer.cart.application.model.CartSku;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import com.commercefoundry.domainlayer.cart.support.InMemoryCartItemRepository;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CartProcessorTest {

    private InMemoryCartItemRepository repository;
    private CartProcessor processor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCartItemRepository();
        processor = new CartProcessor(repository, new SnowflakeIdGenerator(1, 3));
    }

    private void givenLines(long memberId, long fromSkuId, int count) {
        LongStream.range(fromSkuId, fromSkuId + count)
            .forEach(skuId -> repository.givenSaved(new CartItem(skuId * 10, memberId, skuId, 1, CustomOptions.empty(), 0L)));
    }

    private static CartSku sku(long skuId) {
        return cartSku(onSale(skuId, 10_000));
    }

    @Nested
    @DisplayName("담기")
    class AddItem {

        @Test
        @DisplayName("새 줄은 아이디와 맞춤 제작 입력 아이디를 채워 만든다")
        void createsLine() {
            CartSku sku = cartSku(customOrder(onSale(9001, 39_000)));

            processor.addItem(MEMBER_ID, add(9001, 2, options("chest", "96")), sku);

            assertThat(repository.all()).singleElement().satisfies(item -> {
                assertThat(item.memberId()).isEqualTo(MEMBER_ID);
                assertThat(item.skuId()).isEqualTo(9001);
                assertThat(item.quantity()).isEqualTo(2);
                assertThat(item.id()).isPositive();
                assertThat(item.customOptions().values()).extracting(CustomOption::id).doesNotContainNull();
            });
        }

        @Test
        @DisplayName("같은 SKU · 같은 입력이면 그 줄에 수량을 더한다")
        void sameLineAddsQuantity() {
            processor.addItem(MEMBER_ID, add(9001, 2, options("chest", "96")), sku(9001));
            processor.addItem(MEMBER_ID, add(9001, 3, options("chest", " 96")), sku(9001));

            assertThat(repository.all()).singleElement().extracting(CartItem::quantity).isEqualTo(5);
        }

        @Test
        @DisplayName("같은 SKU 라도 입력이 다르면 다른 줄이다")
        void differentOptionsMakeAnotherLine() {
            processor.addItem(MEMBER_ID, add(9001, 1, options("chest", "96")), sku(9001));
            processor.addItem(MEMBER_ID, add(9001, 1, options("chest", "100")), sku(9001));
            processor.addItem(MEMBER_ID, add(9001, 1), sku(9001));

            assertThat(repository.all()).hasSize(3);
        }

        @Test
        @DisplayName("합계가 상한(1회 주문 최대 수량)을 넘으면 CART_QUANTITY_EXCEEDED 이고 줄은 그대로다")
        void rejectsOverMax() {
            CartSku limited = cartSku(withMaxQuantity(onSale(9001, 10_000), 5));
            processor.addItem(MEMBER_ID, add(9001, 4), limited);

            assertThatThrownBy(() -> processor.addItem(MEMBER_ID, add(9001, 2), limited))
                .isInstanceOfSatisfying(CartException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CartErrorCode.CART_QUANTITY_EXCEEDED);
                    assertThat(exception.getMessage()).contains("5");
                });
            assertThat(repository.all()).singleElement().extracting(CartItem::quantity).isEqualTo(4);
        }

        @Test
        @DisplayName("새 줄 수량이 상한을 넘어도 CART_QUANTITY_EXCEEDED 다")
        void rejectsNewLineOverMax() {
            assertThatThrownBy(() -> processor.addItem(MEMBER_ID, add(9001, 6), cartSku(withMaxQuantity(onSale(9001, 10_000), 5))))
                .extracting(exception -> ((CartException) exception).getErrorCode()).isEqualTo(CartErrorCode.CART_QUANTITY_EXCEEDED);
            assertThat(repository.all()).isEmpty();
        }

        @Test
        @DisplayName("50줄이면 새 줄은 CART_ITEM_LIMIT_EXCEEDED 지만, 있는 줄에 더하기는 된다")
        void lineLimit() {
            givenLines(MEMBER_ID, 1, CartItem.MAX_LINES_PER_CART);

            assertThatThrownBy(() -> processor.addItem(MEMBER_ID, add(9999, 1), sku(9999)))
                .extracting(exception -> ((CartException) exception).getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_LIMIT_EXCEEDED);

            processor.addItem(MEMBER_ID, add(1, 1), sku(1));
            assertThat(repository.quantitiesBySkuId(MEMBER_ID)).contains(entry(1L, 2));
        }

        @Test
        @DisplayName("다른 회원의 줄은 세지도 합치지도 않는다")
        void otherMembersLinesAreIgnored() {
            givenLines(OTHER_MEMBER_ID, 1, CartItem.MAX_LINES_PER_CART);

            processor.addItem(MEMBER_ID, add(1, 1), sku(1));

            assertThat(repository.findAllByMemberId(MEMBER_ID)).singleElement().extracting(CartItem::quantity).isEqualTo(1);
            assertThat(repository.findAllByMemberId(OTHER_MEMBER_ID)).hasSize(CartItem.MAX_LINES_PER_CART);
        }
    }

    @Nested
    @DisplayName("수량 변경 · 삭제")
    class ChangeAndRemove {

        @Test
        @DisplayName("내 줄의 수량을 바꾼다")
        void changes() {
            CartItem item = repository.givenSaved(new CartItem(77L, MEMBER_ID, 9001, 1, CustomOptions.empty(), 0L));

            processor.changeQuantity(MEMBER_ID, item.id(), 7, 10);

            assertThat(repository.findByIdAndMemberId(77L, MEMBER_ID)).get().extracting(CartItem::quantity).isEqualTo(7);
        }

        @Test
        @DisplayName("상한을 넘는 수량은 CART_QUANTITY_EXCEEDED 다")
        void rejectsOverMax() {
            repository.givenSaved(new CartItem(77L, MEMBER_ID, 9001, 1, CustomOptions.empty(), 0L));

            assertThatThrownBy(() -> processor.changeQuantity(MEMBER_ID, 77L, 11, 10))
                .extracting(exception -> ((CartException) exception).getErrorCode()).isEqualTo(CartErrorCode.CART_QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("없거나 다른 회원의 줄은 CART_ITEM_NOT_FOUND(404) 다 — 존재를 숨긴다")
        void otherMembersLineIsNotFound() {
            repository.givenSaved(new CartItem(77L, OTHER_MEMBER_ID, 9001, 1, CustomOptions.empty(), 0L));

            assertThatThrownBy(() -> processor.changeQuantity(MEMBER_ID, 77L, 2, 10))
                .extracting(exception -> ((CartException) exception).getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
            assertThatThrownBy(() -> processor.removeItem(MEMBER_ID, 77L))
                .extracting(exception -> ((CartException) exception).getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
            assertThatThrownBy(() -> processor.getItem(MEMBER_ID, 78L))
                .extracting(exception -> ((CartException) exception).getErrorCode()).isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
            assertThat(repository.all()).hasSize(1);
        }

        @Test
        @DisplayName("내 줄을 지운다")
        void removes() {
            repository.givenSaved(new CartItem(77L, MEMBER_ID, 9001, 1, CustomOptions.empty(), 0L));

            processor.removeItem(MEMBER_ID, 77L);

            assertThat(repository.all()).isEmpty();
        }
    }

    @Nested
    @DisplayName("병합")
    class Merge {

        @Test
        @DisplayName("같은 줄은 수량을 더하고, 상한에서 자른다 — 거절하지 않는다")
        void mergesAndCaps() {
            repository.givenSaved(new CartItem(77L, MEMBER_ID, 9001, 8, CustomOptions.empty(), 0L));
            Map<Long, CartSku> skus = Map.of(9001L, cartSku(withMaxQuantity(onSale(9001, 10_000), 10)), 9002L, sku(9002));

            List<CartMergeSkip> skipped = processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9001, 5), add(9002, 3))), skus);

            assertThat(skipped).isEmpty();
            assertThat(repository.quantitiesBySkuId(MEMBER_ID)).containsExactly(entry(9001L, 10), entry(9002L, 3));
        }

        @Test
        @DisplayName("병합 요청 안의 같은 줄도 합친다 — 새 줄 하나로 만들고 상한에서 자른다")
        void mergesWithinRequest() {
            Map<Long, CartSku> skus = Map.of(9001L, cartSku(customOrder(onSale(9001, 10_000))));

            processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9001, 60), add(9001, 60), add(9001, 1, options("chest", "96")))), skus);

            assertThat(repository.findAllByMemberId(MEMBER_ID)).extracting(CartItem::quantity).containsExactlyInAnyOrder(99, 1);
        }

        @Test
        @DisplayName("없는 SKU · 구매할 수 없는 SKU · 맞춤 제작이 아닌데 입력이 있는 항목은 항목 단위로 건너뛰고(위치 · 사유) 나머지는 담는다")
        void skipsInvalid() {
            Map<Long, CartSku> skus = Map.of(9002L, cartSku(soldOut(9002, 10_000)), 9003L, sku(9003), 9004L, sku(9004));
            CartMergeCommand command = new CartMergeCommand(List.of(
                add(9001, 1), add(9002, 1), add(9003, 1, options("chest", "96")), add(9004, 2), add(9001, 1)));

            List<CartMergeSkip> skipped = processor.merge(MEMBER_ID, command, skus);

            assertThat(skipped).containsExactly(
                new CartMergeSkip(0, 9001L, CartMergeSkipReason.SKU_NOT_FOUND),
                new CartMergeSkip(1, 9002L, CartMergeSkipReason.NOT_PURCHASABLE),
                new CartMergeSkip(2, 9003L, CartMergeSkipReason.CUSTOM_OPTION_NOT_ALLOWED),
                new CartMergeSkip(4, 9001L, CartMergeSkipReason.SKU_NOT_FOUND));
            assertThat(repository.quantitiesBySkuId(MEMBER_ID)).containsExactly(entry(9004L, 2));
        }

        @Test
        @DisplayName("같은 SKU 라도 항목마다 판정한다 — 입력이 있는 항목은 건너뛰고(맞춤 제작 아님) 입력이 없는 항목은 담는다")
        void sameSkuOneSkippedOneMerged() {
            Map<Long, CartSku> skus = Map.of(9003L, sku(9003));

            List<CartMergeSkip> skipped = processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9003, 1, options("chest", "96")), add(9003, 2))), skus);

            assertThat(skipped).containsExactly(new CartMergeSkip(0, 9003L, CartMergeSkipReason.CUSTOM_OPTION_NOT_ALLOWED));
            assertThat(repository.findAllByMemberId(MEMBER_ID)).singleElement().satisfies(line -> {
                assertThat(line.skuId()).isEqualTo(9003L);
                assertThat(line.quantity()).isEqualTo(2);
                assertThat(line.customOptions().isEmpty()).isTrue();
            });
        }

        @Test
        @DisplayName("새 줄은 요청 순서대로 아이디가 커진다 — 나중 항목일수록 최근 담은 줄로 위에 보인다")
        void laterItemsAreNewer() {
            Map<Long, CartSku> skus = Map.of(9001L, sku(9001), 9002L, sku(9002), 9003L, sku(9003));

            processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9001, 1), add(9002, 1), add(9003, 1))), skus);

            assertThat(repository.findAllByMemberId(MEMBER_ID)).extracting(CartItem::skuId).containsExactly(9003L, 9002L, 9001L);
        }

        @Test
        @DisplayName("50줄을 넘는 새 줄은 건너뛰지만, 있는 줄과 합치는 항목은 담는다")
        void skipsOverLineLimit() {
            givenLines(MEMBER_ID, 1, CartItem.MAX_LINES_PER_CART - 1);
            Map<Long, CartSku> skus = Map.of(1L, sku(1), 9001L, sku(9001), 9002L, sku(9002));

            List<CartMergeSkip> skipped = processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9001, 1), add(9002, 1), add(1, 4))), skus);

            assertThat(skipped).containsExactly(new CartMergeSkip(1, 9002L, CartMergeSkipReason.LINE_LIMIT_EXCEEDED));
            assertThat(repository.findAllByMemberId(MEMBER_ID)).hasSize(CartItem.MAX_LINES_PER_CART);
            assertThat(repository.quantitiesBySkuId(MEMBER_ID)).contains(entry(1L, 5), entry(9001L, 1));
        }

        @Test
        @DisplayName("바뀐 줄은 한 번에 저장한다 — 루프 안에서 하나씩 저장하지 않는다. 바뀐 것이 없으면 저장하지 않는다")
        void savesOnce() {
            repository.givenSaved(new CartItem(77L, MEMBER_ID, 9001, 99, CustomOptions.empty(), 0L));
            Map<Long, CartSku> skus = Map.of(9001L, sku(9001), 9002L, sku(9002), 9003L, sku(9003));

            processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9001, 1))), skus);
            assertThat(repository.saveCalls()).as("이미 상한인 줄만 있으면 저장하지 않는다").isEmpty();

            processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9002, 1), add(9003, 1), add(9002, 2))), skus);
            assertThat(repository.saveCalls()).singleElement().satisfies(saved -> assertThat(saved).hasSize(2));
        }

        @Test
        @DisplayName("1회 주문 최대 수량이 0 인 상품은 새 줄로 담을 수 없어 건너뛴다")
        void skipsZeroMax() {
            Map<Long, CartSku> skus = Map.of(9001L, cartSku(withMaxQuantity(onSale(9001, 10_000), 0)));

            assertThat(processor.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9001, 1))), skus))
                .containsExactly(new CartMergeSkip(0, 9001L, CartMergeSkipReason.QUANTITY_LIMIT_EXCEEDED));
            assertThat(repository.all()).isEmpty();
        }

        @Test
        @DisplayName("빈 병합은 아무것도 바꾸지 않는다")
        void emptyMerge() {
            assertThat(processor.merge(MEMBER_ID, new CartMergeCommand(new ArrayList<CartItemAddCommand>()), Map.of())).isEmpty();
            assertThat(repository.saveCalls()).isEmpty();
        }
    }
}
