package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.KR;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.address;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.domesticQuote;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static com.commercefoundry.domainlayer.order.support.OrderFixtures.quoteLine;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.order.application.info.OrderPaymentSummaryInfo;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderPaymentSummaryProcessorTest {

    private static final long ORDER_ID = 1001L;
    private static final Instant DUE = Instant.parse("2026-10-18T09:30:00Z");

    private final OrderPaymentSummaryProcessor processor = new OrderPaymentSummaryProcessor();

    private static OrderQuoteLine named(long skuId, String productName) {
        return OrderQuoteLine.of(skuId, skuId + 7000, productName, "", krw(10_000), 1, OrderCustomOptions.empty(), false, null, null);
    }

    private static List<OrderQuoteLine> lines(String firstName, int count) {
        List<OrderQuoteLine> lines = new ArrayList<>();
        lines.add(named(1, firstName));
        for (int i = 2; i <= count; i++) {
            lines.add(named(i, "다른상품" + i));
        }
        return lines;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    @Nested
    @DisplayName("요약")
    class Summarize {

        @Test
        @DisplayName("주문의 아이디 · 회원 · 상태 · 총액 · 결제 기한을 그대로 싣고 주문명을 만든다")
        void carriesOrderValues() {
            AtomicLong ids = new AtomicLong(500);
            Order order = Order.place(ORDER_ID, 7L, domesticQuote(quoteLine(9001, 10_000, 2), quoteLine(9002, 5_000, 1)), ids::incrementAndGet, address(KR),
                99L, DUE);

            OrderPaymentSummaryInfo info = processor.summarize(order);

            assertThat(info.orderId()).isEqualTo(ORDER_ID);
            assertThat(info.memberId()).isEqualTo(7L);
            assertThat(info.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(info.totalAmount()).isEqualTo(order.quote().totalAmount());
            assertThat(info.orderName()).isEqualTo("상품9001 외 1건");
            assertThat(info.paymentDueAt()).isEqualTo(DUE);
        }

        @Test
        @DisplayName("만료된 주문도 상태를 바꾸지 않고 그대로 싣는다")
        void expiredOrderKeepsStatus() {
            AtomicLong ids = new AtomicLong(500);
            Order placed = Order.place(ORDER_ID, 7L, domesticQuote(quoteLine(9001, 10_000, 1)), ids::incrementAndGet, address(KR), 99L, DUE);
            Order expired = placed.expire(DUE.plusSeconds(1));

            assertThat(processor.summarize(expired).status()).isEqualTo(OrderStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("주문명")
    class OrderName {

        @Test
        @DisplayName("줄 1개면 그 줄의 주문 시점 상품명이다")
        void singleLine() {
            assertThat(OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines("생활한복 저고리", 1))).isEqualTo("생활한복 저고리");
        }

        @Test
        @DisplayName("줄 N개면 \"{첫 줄 상품명} 외 {N-1}건\" 이다 — 첫 줄은 목록의 첫 줄이다")
        void multipleLines() {
            assertThat(OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines("생활한복 저고리", 3))).isEqualTo("생활한복 저고리 외 2건");
        }

        @Test
        @DisplayName("상품명 앞뒤 공백은 지운다")
        void stripsName() {
            assertThat(OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines("  저고리  ", 2))).isEqualTo("저고리 외 1건");
        }

        @Test
        @DisplayName("정확히 100 코드포인트면 자르지 않는다")
        void exactlyMaxIsKept() {
            String name = "가".repeat(100);

            assertThat(OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(name, 1))).isEqualTo(name);
        }

        @Test
        @DisplayName("101 코드포인트면 100 으로 자른다")
        void overMaxIsCut() {
            String result = OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines("가".repeat(101), 1));

            assertThat(result).isEqualTo("가".repeat(100));
        }

        @Test
        @DisplayName("접미사와 합쳐 정확히 100 이면 자르지 않는다")
        void exactlyMaxWithSuffix() {
            String suffix = " 외 1건";
            String name = "가".repeat(100 - suffix.length());

            String result = OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(name, 2));

            assertThat(result).isEqualTo(name + suffix);
            assertThat(codePoints(result)).isEqualTo(100);
        }

        @Test
        @DisplayName("긴 첫 상품명은 상품명을 먼저 잘라 접미사 \" 외 N건\" 을 지킨다")
        void longFirstNameKeepsSuffix() {
            String result = OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines("나".repeat(150), 12));

            assertThat(result).endsWith(" 외 11건");
            assertThat(codePoints(result)).isEqualTo(100);
            assertThat(result).isEqualTo("나".repeat(100 - " 외 11건".length()) + " 외 11건");
        }

        @Test
        @DisplayName("이모지(서로게이트 쌍)를 코드포인트 하나로 세고, 쌍 가운데서 자르지 않는다")
        void emojiIsNotSplit() {
            String emoji = "👗"; // 👗 (U+1F457)
            String name = emoji.repeat(120);

            String single = OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(name, 1));
            String multiple = OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(name, 3));

            assertThat(single).isEqualTo(emoji.repeat(100));
            assertThat(single.length()).isEqualTo(200);
            assertThat(multiple).isEqualTo(emoji.repeat(100 - " 외 2건".length()) + " 외 2건");
            assertThat(codePoints(multiple)).isEqualTo(100);
            assertThat(Character.isHighSurrogate(single.charAt(single.length() - 1))).isFalse();
        }

        @Test
        @DisplayName("잘리는 자리가 BMP 글자와 이모지 경계면 홀로 남는 서로게이트가 없다")
        void mixedBoundary() {
            String emoji = "👗";
            String name = "가".repeat(99) + emoji + emoji;

            String result = OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(name, 1));

            assertThat(result).isEqualTo("가".repeat(99) + emoji);
            assertThat(codePoints(result)).isEqualTo(100);
        }

        @Test
        @DisplayName("자른 끝이 공백이면 지운다")
        void cutTrailingSpaceIsStripped() {
            String name = "가".repeat(99) + " 나다라";

            assertThat(OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(name, 1))).isEqualTo("가".repeat(99));
        }

        @ParameterizedTest(name = "[{index}] 공백 이름")
        @ValueSource(strings = {"", " ", "   ", "\t", "　"})
        @DisplayName("첫 상품명이 비었거나 공백뿐이면 \"주문 {orderId}\" 다")
        void blankNameFallsBack(String blank) {
            assertThat(OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(blank, 1))).isEqualTo("주문 1001");
            assertThat(OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines(blank, 3))).isEqualTo("주문 1001");
        }

        @Test
        @DisplayName("50줄이어도 100 코드포인트를 넘지 않는다")
        void manyLines() {
            String result = OrderPaymentSummaryProcessor.orderName(ORDER_ID, lines("다".repeat(100), 50));

            assertThat(result).endsWith(" 외 49건");
            assertThat(codePoints(result)).isEqualTo(100);
        }
    }
}
