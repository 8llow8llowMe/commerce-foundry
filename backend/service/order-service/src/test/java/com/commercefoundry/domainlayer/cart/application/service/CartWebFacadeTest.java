package com.commercefoundry.domainlayer.cart.application.service;

import static com.commercefoundry.domainlayer.cart.support.CartFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.add;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.customOrder;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.onSale;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.options;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.soldOut;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.stopSelling;
import static com.commercefoundry.domainlayer.cart.support.CartFixtures.withMaxQuantity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartLineItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.item.CartMergeSkippedItem;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartMergeResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.presenter.CartWebPresenter;
import com.commercefoundry.domainlayer.cart.application.command.CartMergeCommand;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.service.processor.CartProcessor;
import com.commercefoundry.domainlayer.cart.application.service.processor.CartSkuProcessor;
import com.commercefoundry.domainlayer.cart.application.service.processor.CartViewProcessor;
import com.commercefoundry.domainlayer.cart.support.FakeSkuQueryPort;
import com.commercefoundry.domainlayer.cart.support.InMemoryCartItemRepository;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Facade 부터 Presenter 까지 실제 Processor 로 돌리고 바깥(DB · product-service)만 가짜로 둔다. 장바구니는 가격 · 상태를 저장하지 않으므로
 * product-service 의 값이 바뀌면 다음 조회에 그대로 드러나야 한다 (#12 완료 기준).
 */
class CartWebFacadeTest {

    private InMemoryCartItemRepository repository;
    private FakeSkuQueryPort skuQueryPort;
    private CartWebFacade facade;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCartItemRepository();
        skuQueryPort = new FakeSkuQueryPort();
        facade = new CartWebFacade(
            new CartProcessor(repository, new SnowflakeIdGenerator(1, 3)), new CartSkuProcessor(skuQueryPort), new CartViewProcessor(), new CartWebPresenter());
    }

    private static CartLineItem lineOf(CartResponse cart, long skuId) {
        return cart.items().stream().filter(item -> item.skuId().equals(String.valueOf(skuId))).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("조회에 현재 가격 · 상태가 반영된다")
    class ReflectsCurrentSku {

        @Test
        @DisplayName("담은 뒤 가격이 바뀌면 다음 조회의 단가 · 줄 금액 · 합계가 바뀐다")
        void priceChange() {
            skuQueryPort.put(onSale(9001, 39_000)).put(onSale(9002, 10_000));
            facade.addItem(MEMBER_ID, add(9001, 2));
            facade.addItem(MEMBER_ID, add(9002, 1));

            CartResponse before = facade.getCart(MEMBER_ID);
            assertThat(lineOf(before, 9001).unitPrice()).isEqualTo(MoneyItem.of(39_000, "KRW"));
            assertThat(lineOf(before, 9001).lineAmount()).isEqualTo(MoneyItem.of(78_000, "KRW"));
            assertThat(before.totalAmount()).isEqualTo(MoneyItem.of(88_000, "KRW"));

            skuQueryPort.put(onSale(9001, 35_000));
            CartResponse after = facade.getCart(MEMBER_ID);

            assertThat(lineOf(after, 9001).unitPrice()).isEqualTo(MoneyItem.of(35_000, "KRW"));
            assertThat(lineOf(after, 9001).lineAmount()).isEqualTo(MoneyItem.of(70_000, "KRW"));
            assertThat(after.totalAmount()).isEqualTo(MoneyItem.of(80_000, "KRW"));
            assertThat(after.purchasableCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("담은 뒤 품절 · 판매 종료되면 상태가 바뀌고 합계에서 빠진다 — 줄은 남는다")
        void soldOutAndStopSelling() {
            skuQueryPort.put(onSale(9001, 39_000)).put(onSale(9002, 10_000)).put(onSale(9003, 5_000));
            facade.addItem(MEMBER_ID, add(9001, 1));
            facade.addItem(MEMBER_ID, add(9002, 1));
            facade.addItem(MEMBER_ID, add(9003, 1));
            assertThat(facade.getCart(MEMBER_ID).totalAmount()).isEqualTo(MoneyItem.of(54_000, "KRW"));

            skuQueryPort.put(soldOut(9001, 39_000)).put(stopSelling(9002, 10_000));
            CartResponse cart = facade.getCart(MEMBER_ID);

            assertThat(cart.items()).hasSize(3);
            assertThat(lineOf(cart, 9001).status().code()).isEqualTo("SOLD_OUT");
            assertThat(lineOf(cart, 9002).status().code()).isEqualTo("STOP_SELLING");
            assertThat(lineOf(cart, 9003).status().code()).isEqualTo("ON_SALE");
            // 품절 줄도 단가 · 줄 금액은 보여 준다 — 합계에서만 빠진다
            assertThat(lineOf(cart, 9001).lineAmount()).isEqualTo(MoneyItem.of(39_000, "KRW"));
            assertThat(cart.totalAmount()).isEqualTo(MoneyItem.of(5_000, "KRW"));
            assertThat(cart.purchasableCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("SKU 가 사라지면 상품 정보 없이 판매 중지로 보여 주고 합계에서 뺀다. 구매 가능 줄이 없으면 합계는 null 이다")
        void missingSku() {
            skuQueryPort.put(onSale(9001, 39_000));
            facade.addItem(MEMBER_ID, add(9001, 1));

            skuQueryPort.remove(9001);
            CartResponse cart = facade.getCart(MEMBER_ID);

            CartLineItem line = lineOf(cart, 9001);
            assertThat(line.status().code()).isEqualTo("STOP_SELLING");
            assertThat(line.productId()).isNull();
            assertThat(line.productName()).isNull();
            assertThat(line.unitPrice()).isNull();
            assertThat(line.lineAmount()).isNull();
            assertThat(line.quantity()).isEqualTo(1);
            assertThat(cart.totalAmount()).isNull();
            assertThat(cart.purchasableCount()).isZero();
        }

        @Test
        @DisplayName("담은 뒤 1회 주문 최대 수량이 줄면 quantityExceeded 이고 합계에서 빠진다")
        void maxQuantityShrunk() {
            skuQueryPort.put(onSale(9001, 10_000));
            facade.addItem(MEMBER_ID, add(9001, 5));

            skuQueryPort.put(withMaxQuantity(onSale(9001, 10_000), 3));
            CartLineItem line = lineOf(facade.getCart(MEMBER_ID), 9001);

            assertThat(line.quantityExceeded()).isTrue();
            assertThat(line.maxQuantityPerOrder()).isEqualTo(3);
            assertThat(facade.getCart(MEMBER_ID).totalAmount()).isNull();
        }
    }

    @Test
    @DisplayName("조회는 줄 수와 무관하게 product-service 를 한 번만 부르고, 빈 장바구니면 부르지 않는다")
    void bulkLookup() {
        CartResponse empty = facade.getCart(MEMBER_ID);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalAmount()).isNull();
        assertThat(skuQueryPort.calls()).isEmpty();

        skuQueryPort.put(onSale(9001, 1_000)).put(onSale(9002, 1_000)).put(customOrder(onSale(9003, 1_000)));
        facade.addItem(MEMBER_ID, add(9001, 1));
        facade.addItem(MEMBER_ID, add(9002, 1));
        facade.addItem(MEMBER_ID, add(9003, 1, options("chest", "96")));
        facade.addItem(MEMBER_ID, add(9003, 1));
        skuQueryPort.calls().clear();

        CartResponse cart = facade.getCart(MEMBER_ID);

        assertThat(cart.items()).hasSize(4);
        assertThat(skuQueryPort.calls()).singleElement().satisfies(ids -> assertThat(ids).containsExactlyInAnyOrder(9001L, 9002L, 9003L));
    }

    @Test
    @DisplayName("응답 아이디는 문자열이고, 줄은 최근에 담은 순이다")
    void responseShape() {
        skuQueryPort.put(customOrder(onSale(9001, 1_000))).put(onSale(9002, 1_000));
        facade.addItem(MEMBER_ID, add(9001, 1, options("chest", "96")));
        CartResponse cart = facade.addItem(MEMBER_ID, add(9002, 1));

        assertThat(cart.items()).extracting(CartLineItem::skuId).containsExactly("9002", "9001");
        CartLineItem first = lineOf(cart, 9001);
        assertThat(first.itemId()).matches("\\d+");
        assertThat(first.productId()).isEqualTo("16001");
        assertThat(first.customOrder()).isTrue();
        assertThat(first.customOptions()).singleElement().satisfies(option -> {
            assertThat(option.fieldKey()).isEqualTo("chest");
            assertThat(option.value()).isEqualTo("96");
        });
    }

    @Nested
    @DisplayName("담기 거절")
    class AddRejections {

        @Test
        @DisplayName("없는 SKU 404 · 품절 409 · 판매 종료 409 · 맞춤 제작 아님 400 — DB 에 닿지 않는다")
        void rejects() {
            skuQueryPort.put(soldOut(9002, 1_000)).put(stopSelling(9003, 1_000)).put(onSale(9004, 1_000));

            assertCode(() -> facade.addItem(MEMBER_ID, add(9001, 1)), CartErrorCode.CART_SKU_NOT_FOUND);
            assertCode(() -> facade.addItem(MEMBER_ID, add(9002, 1)), CartErrorCode.CART_SKU_NOT_PURCHASABLE);
            assertCode(() -> facade.addItem(MEMBER_ID, add(9003, 1)), CartErrorCode.CART_SKU_NOT_PURCHASABLE);
            assertCode(() -> facade.addItem(MEMBER_ID, add(9004, 1, options("chest", "96"))), CartErrorCode.CART_CUSTOM_OPTION_NOT_ALLOWED);
            assertThat(repository.saveCalls()).isEmpty();
        }

        @Test
        @DisplayName("품절된 줄도 수량을 바꾸고 지울 수 있다")
        void soldOutLineCanBeChangedAndRemoved() {
            skuQueryPort.put(onSale(9001, 1_000));
            CartResponse cart = facade.addItem(MEMBER_ID, add(9001, 1));
            long itemId = Long.parseLong(cart.items().getFirst().itemId());

            skuQueryPort.put(soldOut(9001, 1_000));
            assertThat(facade.changeQuantity(MEMBER_ID, itemId, 3).items().getFirst().quantity()).isEqualTo(3);
            assertThat(facade.removeItem(MEMBER_ID, itemId).items()).isEmpty();
        }

        @Test
        @DisplayName("SKU 가 사라진 줄의 수량 상한은 99 다")
        void missingSkuChangeUsesDefaultMax() {
            skuQueryPort.put(onSale(9001, 1_000));
            long itemId = Long.parseLong(facade.addItem(MEMBER_ID, add(9001, 1)).items().getFirst().itemId());
            skuQueryPort.remove(9001);

            assertThat(facade.changeQuantity(MEMBER_ID, itemId, 99).items().getFirst().quantity()).isEqualTo(99);
        }

        @Test
        @DisplayName("1회 주문 최대 수량을 넘는 수량 변경은 CART_QUANTITY_EXCEEDED 다")
        void changeOverMax() {
            skuQueryPort.put(withMaxQuantity(onSale(9001, 1_000), 3));
            long itemId = Long.parseLong(facade.addItem(MEMBER_ID, add(9001, 1)).items().getFirst().itemId());

            assertCode(() -> facade.changeQuantity(MEMBER_ID, itemId, 4), CartErrorCode.CART_QUANTITY_EXCEEDED);
        }
    }

    @Test
    @DisplayName("병합은 SKU 를 한 번에 조회하고, 건너뛴 항목을 위치 · 문자열 SKU 아이디 · 사유로 돌려준다")
    void merge() {
        skuQueryPort.put(onSale(9001, 1_000)).put(soldOut(9002, 1_000));

        CartMergeResponse response = facade.merge(MEMBER_ID, new CartMergeCommand(List.of(add(9001, 2), add(9002, 1), add(9003, 1))));

        assertThat(skuQueryPort.calls().getFirst()).containsExactly(9001L, 9002L, 9003L);
        assertThat(skuQueryPort.calls()).as("병합 1회 + 병합 후 장바구니 조회 1회").hasSize(2);
        assertThat(response.skippedItems()).containsExactly(
            CartMergeSkippedItem.builder().index(1).skuId("9002").reason("NOT_PURCHASABLE").build(),
            CartMergeSkippedItem.builder().index(2).skuId("9003").reason("SKU_NOT_FOUND").build());
        assertThat(response.cart().items()).singleElement().satisfies(line -> {
            assertThat(line.skuId()).isEqualTo("9001");
            assertThat(line.quantity()).isEqualTo(2);
        });
    }

    private static void assertCode(ThrowingCallable call, CartErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CartException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
