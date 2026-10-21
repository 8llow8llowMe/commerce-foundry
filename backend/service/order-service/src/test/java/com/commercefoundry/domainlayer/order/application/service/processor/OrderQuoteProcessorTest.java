package com.commercefoundry.domainlayer.order.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderQuoteCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.ShippingFeeQuote;
import com.commercefoundry.domainlayer.order.domain.model.ShippingScope;
import com.commercefoundry.domainlayer.order.support.FakeShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.support.FakeShippingFeeQueryPort.DestinationCheck;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OrderQuoteProcessorTest {

    private static final String HS_CODE = "6211420000";
    private static final String KR_POSTAL_CODE = "63100";

    private FakeShippingFeeQueryPort shippingFeeQueryPort;
    private OrderQuoteProcessor processor;

    @BeforeEach
    void setUp() {
        shippingFeeQueryPort = new FakeShippingFeeQueryPort();
        processor = new OrderQuoteProcessor(shippingFeeQueryPort);
    }

    private static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    private static Money usd(long amount) {
        return Money.of(amount, CurrencyCode.USD);
    }

    /** 구매 가능 · 일반 상품 · 1회 주문 최대 수량 없음 · 해외 정보(무게 500g · HS 코드) 있음. */
    private static CatalogSku sku(long skuId, long productId, String productName, String optionName, Money price) {
        return new CatalogSku(skuId, productId, productName, optionName, price, true, false, null, false, 500, HS_CODE);
    }

    private static CatalogSku sku(long skuId, long productId, String productName, String optionName, long priceKrw) {
        return sku(skuId, productId, productName, optionName, krw(priceKrw));
    }

    /** 품절이라 구매할 수 없다 — 이름은 알린다. */
    private static CatalogSku soldOut(CatalogSku sku) {
        return new CatalogSku(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            false, true, sku.maxQuantityPerOrder(), sku.customOrder(), sku.weightGrams(), sku.hsCode());
    }

    /** 품절이 아닌데 구매할 수 없다(숨김 · 판매 종료) — 이름을 알리지 않는다. */
    private static CatalogSku hidden(CatalogSku sku) {
        return new CatalogSku(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            false, false, sku.maxQuantityPerOrder(), sku.customOrder(), sku.weightGrams(), sku.hsCode());
    }

    private static CatalogSku customOrder(CatalogSku sku) {
        return new CatalogSku(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            sku.purchasable(), sku.soldOut(), sku.maxQuantityPerOrder(), true, sku.weightGrams(), sku.hsCode());
    }

    private static CatalogSku withMaxQuantity(CatalogSku sku, Integer maxQuantityPerOrder) {
        return new CatalogSku(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            sku.purchasable(), sku.soldOut(), maxQuantityPerOrder, sku.customOrder(), sku.weightGrams(), sku.hsCode());
    }

    private static CatalogSku withShippingInfo(CatalogSku sku, Integer weightGrams, String hsCode) {
        return new CatalogSku(sku.skuId(), sku.productId(), sku.productName(), sku.optionName(), sku.salePrice(),
            sku.purchasable(), sku.soldOut(), sku.maxQuantityPerOrder(), sku.customOrder(), weightGrams, hsCode);
    }

    private static Map<Long, CatalogSku> skus(CatalogSku... skus) {
        Map<Long, CatalogSku> found = new LinkedHashMap<>();
        for (CatalogSku sku : skus) {
            found.put(sku.skuId(), sku);
        }
        return found;
    }

    private static OrderItemCommand item(long skuId, int quantity) {
        return new OrderItemCommand(skuId, quantity, OrderCustomOptions.empty());
    }

    private static OrderItemCommand item(long skuId, int quantity, String fieldKey, String value) {
        return new OrderItemCommand(skuId, quantity, OrderCustomOptions.of(List.of(new OrderCustomOption(fieldKey, value))));
    }

    private static OrderQuoteCommand domestic(OrderItemCommand... items) {
        return new OrderQuoteCommand(List.of(items), "KR", KR_POSTAL_CODE);
    }

    private static OrderQuoteCommand international(OrderItemCommand... items) {
        return new OrderQuoteCommand(List.of(items), "US", null);
    }

    private static OrderException rejected(ThrowingCallable call, OrderErrorCode expected) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
        return (OrderException) thrown;
    }

    private void assertNoShippingCall() {
        assertThat(shippingFeeQueryPort.quoteCalls()).isEmpty();
        assertThat(shippingFeeQueryPort.destinationChecks()).isEmpty();
    }

    @Nested
    @DisplayName("주문 항목 판정 — 처음 걸리는 단계 하나로 거절하고, 그 단계에 걸린 줄을 모두 이름으로 알린다")
    class ItemRules {

        @Test
        @DisplayName("같은 SKU · 같은 맞춤 입력(앞뒤 공백 · NFD 정규화 후)의 줄이 두 번 있으면 ORDER_008 이다 — 입력이 다르면 다른 줄이다")
        void duplicateLine() {
            CatalogSku jacket = customOrder(sku(1001, 7001, "저고리", "S / 남색", 30_000));
            String memo = "소매 길게";

            OrderException duplicated = rejected(() -> processor.quote(domestic(
                item(1001, 1, "memo", memo), item(1001, 2, " memo ", Normalizer.normalize(memo, Normalizer.Form.NFD) + " ")), skus(jacket)),
                OrderErrorCode.DUPLICATE_LINE);
            assertThat(duplicated.getMessage()).isEqualTo("같은 상품이 주문에 중복되어 있습니다: 저고리(S / 남색)");
            assertThat(duplicated.getMessage()).doesNotContain("1001");
            rejected(() -> processor.quote(domestic(item(1001, 1), item(1001, 1)), skus(jacket)), OrderErrorCode.DUPLICATE_LINE);
            assertNoShippingCall();

            OrderQuote quote = processor.quote(domestic(item(1001, 1, "memo", memo), item(1001, 1, "memo", "소매 짧게"), item(1001, 1)), skus(jacket));
            assertThat(quote.lines()).hasSize(3);
        }

        @Test
        @DisplayName("없는 SKU · 구매할 수 없는 SKU 를 모두 모아 ORDER_003 이다 — 없는 SKU 는 '찾을 수 없는 상품' 이고 SKU 아이디는 넣지 않는다")
        void itemNotPurchasable() {
            CatalogSku onSale = sku(1001, 7001, "저고리", "S / 남색", 30_000);
            CatalogSku soldOut = soldOut(sku(1002, 7002, "치마", "", 40_000));

            OrderException exception = rejected(
                () -> processor.quote(domestic(item(1001, 1), item(9999, 1), item(1002, 1), item(9998, 1)), skus(onSale, soldOut)),
                OrderErrorCode.ITEM_NOT_PURCHASABLE);

            assertThat(exception.getMessage()).isEqualTo("지금은 구매할 수 없는 상품이 있습니다: %s, 치마".formatted(OrderQuoteProcessor.UNKNOWN_ITEM_NAME));
            assertThat(exception.getMessage()).doesNotContain("9999", "1002");
            assertNoShippingCall();
        }

        @Test
        @DisplayName("품절이 아닌데 구매할 수 없는 SKU(숨김 · 판매 종료)는 '판매하지 않는 상품' 이다 — 숨긴 상품명이 ORDER_003 · ORDER_008 메시지에 없다. 품절은 이름을 알린다")
        void hiddenItemName() {
            CatalogSku hidden = hidden(sku(1001, 7001, "비공개 신상 저고리", "S", 30_000));
            CatalogSku soldOut = soldOut(sku(1002, 7002, "치마", "", 40_000));

            OrderException notPurchasable = rejected(
                () -> processor.quote(domestic(item(1001, 1), item(1002, 1), item(9999, 1)), skus(hidden, soldOut)), OrderErrorCode.ITEM_NOT_PURCHASABLE);
            assertThat(notPurchasable.getMessage()).isEqualTo("지금은 구매할 수 없는 상품이 있습니다: %s, 치마, %s"
                .formatted(OrderQuoteProcessor.NOT_ON_SALE_ITEM_NAME, OrderQuoteProcessor.UNKNOWN_ITEM_NAME));
            assertThat(notPurchasable.getMessage()).doesNotContain("비공개", "1001");

            // 중복 줄 판정이 판매 가능 판정보다 먼저다 — 중복 메시지도 같은 이름 규칙을 거친다
            OrderException duplicated = rejected(() -> processor.quote(domestic(item(1001, 1), item(1001, 2)), skus(hidden)), OrderErrorCode.DUPLICATE_LINE);
            assertThat(duplicated.getMessage()).isEqualTo("같은 상품이 주문에 중복되어 있습니다: " + OrderQuoteProcessor.NOT_ON_SALE_ITEM_NAME);
            assertThat(duplicated.getMessage()).doesNotContain("비공개", "1001");
            assertNoShippingCall();
        }

        @Test
        @DisplayName("맞춤 제작 상품이 아닌데 맞춤 입력이 있으면 ORDER_004 이다")
        void customOptionNotAllowed() {
            CatalogSku plain = sku(1001, 7001, "저고리", "S", 30_000);
            CatalogSku custom = customOrder(sku(1002, 7002, "두루마기", "M", 90_000));

            OrderException exception = rejected(
                () -> processor.quote(domestic(item(1001, 1, "chest", "96"), item(1002, 1, "chest", "96")), skus(plain, custom)),
                OrderErrorCode.CUSTOM_OPTION_NOT_ALLOWED);

            assertThat(exception.getMessage()).endsWith(": 저고리(S)");
            assertNoShippingCall();
        }

        @Test
        @DisplayName("1회 주문 최대 수량은 상품 단위로 센다 — 같은 상품의 S 2 + M 2 는 상한 3 을 넘어 ORDER_005 이고, 메시지에 상품명 · 상한이 있다")
        void quantityPerProduct() {
            CatalogSku small = withMaxQuantity(sku(1001, 7001, "치마", "S", 40_000), 3);
            CatalogSku medium = withMaxQuantity(sku(1002, 7001, "치마", "M", 40_000), 3);
            CatalogSku other = sku(1003, 7003, "저고리", "S", 30_000);

            OrderException exception = rejected(
                () -> processor.quote(domestic(item(1001, 2), item(1003, 5), item(1002, 2)), skus(small, medium, other)), OrderErrorCode.QUANTITY_EXCEEDED);

            assertThat(exception.getMessage()).isEqualTo("한 번에 주문할 수 있는 수량을 넘은 상품이 있습니다: 치마(최대 3개)");
            assertNoShippingCall();
            assertThat(processor.quote(domestic(item(1001, 2), item(1002, 1)), skus(small, medium)).lines()).as("합이 상한과 같으면 통과").hasSize(2);
        }

        @Test
        @DisplayName("같은 SKU 를 다른 맞춤 입력으로 나눈 줄도 더한다 — 상한이 없으면(null) 제한하지 않고, 상한 0 이면 한 벌도 안 된다")
        void quantityAcrossCustomOptionLines() {
            CatalogSku custom = withMaxQuantity(customOrder(sku(1001, 7001, "두루마기", "M", 90_000)), 2);

            rejected(() -> processor.quote(domestic(item(1001, 1, "chest", "96"), item(1001, 2, "chest", "100")), skus(custom)),
                OrderErrorCode.QUANTITY_EXCEEDED);

            CatalogSku unlimited = withMaxQuantity(custom, null);
            assertThat(processor.quote(domestic(item(1001, 99, "chest", "96"), item(1001, 99, "chest", "100")), skus(unlimited)).lines()).hasSize(2);

            CatalogSku closed = withMaxQuantity(custom, 0);
            rejected(() -> processor.quote(domestic(item(1001, 1, "chest", "96")), skus(closed)), OrderErrorCode.QUANTITY_EXCEEDED);
        }

        @Test
        @DisplayName("같은 상품의 SKU 상한이 다르게 오면 작은 쪽(닫힌 쪽)을 쓴다 — 3 과 5 면 3, 제한 없음(null)과 2 면 2. 줄 순서와 무관하다")
        void quantityLimitTakesStricter() {
            CatalogSku three = withMaxQuantity(sku(1001, 7001, "치마", "S", 40_000), 3);
            CatalogSku five = withMaxQuantity(sku(1002, 7001, "치마", "M", 40_000), 5);

            OrderException exceeded = rejected(() -> processor.quote(domestic(item(1002, 2), item(1001, 2)), skus(three, five)), OrderErrorCode.QUANTITY_EXCEEDED);
            assertThat(exceeded.getMessage()).endsWith(": 치마(최대 3개)");
            rejected(() -> processor.quote(domestic(item(1001, 2), item(1002, 2)), skus(three, five)), OrderErrorCode.QUANTITY_EXCEEDED);

            CatalogSku unlimited = withMaxQuantity(sku(1003, 7003, "저고리", "S", 30_000), null);
            CatalogSku two = withMaxQuantity(sku(1004, 7003, "저고리", "M", 30_000), 2);
            OrderException closed = rejected(() -> processor.quote(domestic(item(1003, 2), item(1004, 1)), skus(unlimited, two)), OrderErrorCode.QUANTITY_EXCEEDED);
            assertThat(closed.getMessage()).endsWith(": 저고리(최대 2개)");
            rejected(() -> processor.quote(domestic(item(1004, 1), item(1003, 2)), skus(unlimited, two)), OrderErrorCode.QUANTITY_EXCEEDED);
            assertNoShippingCall();
            assertThat(processor.quote(domestic(item(1003, 1), item(1004, 1)), skus(unlimited, two)).lines()).as("합이 작은 상한과 같으면 통과").hasSize(2);
        }

        @Test
        @DisplayName("판매가 통화가 섞이면 ORDER_006 이다")
        void itemCurrencyMixed() {
            CatalogSku won = sku(1001, 7001, "저고리", "S", 30_000);
            CatalogSku dollar = sku(1002, 7002, "치마", "S", Money.of(4_000, CurrencyCode.USD));

            rejected(() -> processor.quote(domestic(item(1001, 1), item(1002, 1)), skus(won, dollar)), OrderErrorCode.CURRENCY_MIXED);
            assertNoShippingCall();
        }

        @Test
        @DisplayName("판정 순서 — 중복 줄 → 구매 불가 → 맞춤 입력 불가 → 상품 수량 상한 → 통화 → 해외 정보. 앞 단계가 걸리면 뒤 단계 · 배송비 견적을 보지 않는다")
        void order() {
            CatalogSku plain = sku(1001, 7001, "저고리", "S", 30_000);
            CatalogSku limited = withMaxQuantity(sku(1002, 7002, "치마", "S", 40_000), 1);
            CatalogSku dollar = sku(1003, 7003, "버선", "", Money.of(1_000, CurrencyCode.USD));
            CatalogSku noShippingInfo = withShippingInfo(sku(1004, 7004, "배자", "", 50_000), null, null);
            Map<Long, CatalogSku> found = skus(plain, limited, dollar, noShippingInfo);

            rejected(() -> processor.quote(domestic(item(1001, 1), item(1001, 1), item(9999, 1)), found), OrderErrorCode.DUPLICATE_LINE);
            rejected(() -> processor.quote(domestic(item(9999, 1), item(1001, 1, "chest", "96")), found), OrderErrorCode.ITEM_NOT_PURCHASABLE);
            rejected(() -> processor.quote(domestic(item(1001, 1, "chest", "96"), item(1002, 2)), found), OrderErrorCode.CUSTOM_OPTION_NOT_ALLOWED);
            rejected(() -> processor.quote(domestic(item(1002, 2), item(1003, 1)), found), OrderErrorCode.QUANTITY_EXCEEDED);
            rejected(() -> processor.quote(international(item(1003, 1), item(1004, 1)), found), OrderErrorCode.CURRENCY_MIXED);
            // 없는 SKU 와 해외 정보 미입력이 같이 있으면 구매 불가가 먼저다
            rejected(() -> processor.quote(international(item(9999, 1), item(1004, 1)), found), OrderErrorCode.ITEM_NOT_PURCHASABLE);
            assertNoShippingCall();
        }
    }

    @Nested
    @DisplayName("금액 · 국내 배송비 견적")
    class Amounts {

        @Test
        @DisplayName("줄 금액 = 단가 × 수량, 상품 합계 = Σ 줄 금액, 총액 = 상품 합계 + 배송비. 줄은 요청 순서이고 주문 시점 SKU 값을 담는다")
        void amounts() {
            CatalogSku jacket = customOrder(sku(1001, 7001, "저고리", "S / 남색", 12_000));
            CatalogSku skirt = sku(1002, 7002, "치마", "", 30_500);

            OrderQuote quote = processor.quote(domestic(item(1002, 1), item(1001, 2, "chest", "96")), skus(jacket, skirt));

            assertThat(quote.currency()).isEqualTo(CurrencyCode.KRW);
            assertThat(quote.lines()).containsExactly(
                new OrderQuoteLine(1002, 7002, "치마", "", krw(30_500), 1, krw(30_500), OrderCustomOptions.empty(), false, 500, HS_CODE),
                new OrderQuoteLine(1001, 7001, "저고리", "S / 남색", krw(12_000), 2, krw(24_000),
                    OrderCustomOptions.of(List.of(new OrderCustomOption("chest", "96"))), true, 500, HS_CODE));
            assertThat(quote.itemsAmount()).isEqualTo(krw(54_500));
            assertThat(quote.shippingFeeQuote().shippingFee()).isEqualTo(krw(FakeShippingFeeQueryPort.DOMESTIC_FEE));
            assertThat(quote.totalAmount()).isEqualTo(krw(57_500));
            assertThat(quote.countryCode()).isEqualTo("KR");
        }

        @Test
        @DisplayName("국내 견적은 상품 합계 · 우편번호를 싣고 무게는 SKU 에 값이 있어도 싣지 않는다. 견적 결과를 그대로 domain 값으로 옮기고 총 무게는 null 이다")
        void domesticQuery() {
            shippingFeeQueryPort.willQuote(new ShippingFeeQuoteQueryResult(
                krw(6_000), krw(3_000), false, krw(3_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null));

            OrderQuote quote = processor.quote(domestic(item(1001, 2)), skus(sku(1001, 7001, "저고리", "S", 20_000)));

            assertThat(shippingFeeQueryPort.quoteCalls()).containsExactly(new ShippingFeeQuoteQuery(krw(40_000), "KR", KR_POSTAL_CODE, null));
            assertThat(shippingFeeQueryPort.destinationChecks()).isEmpty();
            assertThat(quote.shippingFeeQuote()).isEqualTo(new ShippingFeeQuote(
                krw(6_000), krw(3_000), false, krw(3_000), "제주", krw(50_000), 3L, ShippingScope.DOMESTIC, null, null, null));
            assertThat(quote.totalAmount()).isEqualTo(krw(46_000));
        }

        @Test
        @DisplayName("국내 주문은 무게 · HS 코드가 없어도 거절하지 않는다 — 무료배송이면 총액이 상품 합계다")
        void domesticIgnoresShippingInfo() {
            shippingFeeQueryPort.willQuote(new ShippingFeeQuoteQueryResult(
                krw(0), krw(3_000), true, krw(0), null, krw(50_000), 3L, ShippingScope.DOMESTIC, null, null));
            CatalogSku noShippingInfo = withShippingInfo(sku(1001, 7001, "저고리", "S", 60_000), null, null);
            CatalogSku zeroWeight = withShippingInfo(sku(1002, 7002, "치마", "S", 10_000), 0, HS_CODE);

            OrderQuote quote = processor.quote(domestic(item(1001, 1), item(1002, 1)), skus(noShippingInfo, zeroWeight));

            assertThat(quote.totalAmount()).isEqualTo(krw(70_000));
            assertThat(shippingFeeQueryPort.quoteCalls()).singleElement().satisfies(query -> assertThat(query.weightGrams()).isNull());
        }

        @Test
        @DisplayName("배송비 견적의 거절은 그대로 던진다 — 예: 영문 스토어(USD) 주문 + 국내 배송지는 ORDER_014")
        void quoteRejectionPropagates() {
            shippingFeeQueryPort.willRejectQuote(OrderErrorCode.DESTINATION_CURRENCY_NOT_SUPPORTED);
            CatalogSku dollar = sku(1001, 7001, "저고리", "S", Money.of(9_900, CurrencyCode.USD));

            rejected(() -> processor.quote(domestic(item(1001, 1)), skus(dollar)), OrderErrorCode.DESTINATION_CURRENCY_NOT_SUPPORTED);
            assertThat(shippingFeeQueryPort.quoteCalls()).singleElement().satisfies(query -> assertThat(query.orderAmount()).isEqualTo(Money.of(9_900, CurrencyCode.USD)));
        }
    }

    @Nested
    @DisplayName("해외 배송비 견적 — 배송 가능 국가 → 무게 · HS 미입력 → 총 무게 → 견적 (#80)")
    class International {

        @Test
        @DisplayName("총 무게 = Σ(SKU 무게 × 수량) 을 견적에 싣고 우편번호가 없으면 null 이다. 무게 · HS 가 모두 있으면 배송 가능 국가 확인 없이 견적한다. 통화는 보지 않는다")
        void totalWeight() {
            CatalogSku jacket = withShippingInfo(sku(1001, 7001, "저고리", "S", 30_000), 500, HS_CODE);
            CatalogSku skirt = withShippingInfo(sku(1002, 7002, "치마", "M", 40_000), 300, "621142");

            OrderQuote quote = processor.quote(international(item(1001, 2), item(1002, 3)), skus(jacket, skirt));

            assertThat(shippingFeeQueryPort.quoteCalls()).containsExactly(new ShippingFeeQuoteQuery(krw(180_000), "US", null, 1_900));
            assertThat(shippingFeeQueryPort.destinationChecks()).isEmpty();
            assertThat(quote.shippingFeeQuote().scope()).isEqualTo(ShippingScope.INTERNATIONAL);
            assertThat(quote.shippingFeeQuote().totalWeightGrams()).isEqualTo(1_900);
            assertThat(quote.totalAmount()).isEqualTo(krw(180_000 + FakeShippingFeeQueryPort.INTERNATIONAL_BASE_FEE + FakeShippingFeeQueryPort.INTERNATIONAL_WEIGHT_FEE));
        }

        @Test
        @DisplayName("해외 무료배송 — 배송비 0 · 기본료 · 무게 요금 · 무료배송 기준을 그대로 옮기고 총 무게를 싣는다. 총액은 상품 합계다")
        void internationalFreeShipping() {
            shippingFeeQueryPort.willQuote(new ShippingFeeQuoteQueryResult(
                usd(0), usd(1_500), true, usd(0), null, usd(10_000), 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000)));
            CatalogSku jacket = sku(1001, 7001, "저고리", "S", usd(6_000));

            OrderQuote quote = processor.quote(international(item(1001, 2)), skus(jacket));

            assertThat(shippingFeeQueryPort.quoteCalls()).containsExactly(new ShippingFeeQuoteQuery(usd(12_000), "US", null, 1_000));
            assertThat(quote.shippingFeeQuote()).isEqualTo(new ShippingFeeQuote(
                usd(0), usd(1_500), true, usd(0), null, usd(10_000), 7L, ShippingScope.INTERNATIONAL, "북미", usd(3_000), 1_000));
            assertThat(quote.itemsAmount()).isEqualTo(usd(12_000));
            assertThat(quote.totalAmount()).isEqualTo(usd(12_000));
        }

        @Test
        @DisplayName("무게 · HS 가 모두 있으면 확인 없이 견적하고, 배송할 수 없는 국가는 그 견적의 거절(ORDER_013)로 그대로 전파된다. 총 무게 int 초과(ORDER_015)는 견적 전이라 앞선다")
        void quoteRejectsUnsupportedCountry() {
            shippingFeeQueryPort.willRejectQuote(OrderErrorCode.DESTINATION_NOT_SUPPORTED);

            rejected(() -> processor.quote(international(item(1001, 1)), skus(sku(1001, 7001, "저고리", "S", 30_000))), OrderErrorCode.DESTINATION_NOT_SUPPORTED);
            assertThat(shippingFeeQueryPort.destinationChecks()).isEmpty();
            assertThat(shippingFeeQueryPort.quoteCalls()).singleElement().satisfies(query -> assertThat(query.weightGrams()).isEqualTo(500));
            shippingFeeQueryPort.quoteCalls().clear();

            CatalogSku heavy = withShippingInfo(sku(1002, 7002, "궤짝", "", 30_000), 1_000_000_000, HS_CODE);
            rejected(() -> processor.quote(international(item(1002, 3)), skus(heavy)), OrderErrorCode.SHIPPING_WEIGHT_NOT_SUPPORTED);
            assertNoShippingCall();
        }

        @Test
        @DisplayName("해외 우편번호가 있으면 견적에 싣는다")
        void postalCode() {
            CatalogSku jacket = sku(1001, 7001, "저고리", "S", Money.of(4_000, CurrencyCode.USD));

            processor.quote(new OrderQuoteCommand(List.of(item(1001, 1)), "GB", "SW1A 1AA"), skus(jacket));

            assertThat(shippingFeeQueryPort.quoteCalls()).containsExactly(new ShippingFeeQuoteQuery(Money.of(4_000, CurrencyCode.USD), "GB", "SW1A 1AA", 500));
        }

        @Test
        @DisplayName("총 무게가 int 범위 안이면(경계 Integer.MAX_VALUE) 그대로 싣고, 넘으면 견적을 부르지 않고 ORDER_015 다 — long 으로 더해 넘침을 놓치지 않는다")
        void weightOverflow() {
            CatalogSku heaviest = withShippingInfo(sku(1001, 7001, "장롱", "", 30_000), Integer.MAX_VALUE, HS_CODE);
            processor.quote(international(item(1001, 1)), skus(heaviest));
            assertThat(shippingFeeQueryPort.quoteCalls()).singleElement().satisfies(query -> assertThat(query.weightGrams()).isEqualTo(Integer.MAX_VALUE));
            shippingFeeQueryPort.quoteCalls().clear();

            CatalogSku heavy = withShippingInfo(sku(1002, 7002, "궤짝", "", 30_000), 1_000_000_000, HS_CODE);
            rejected(() -> processor.quote(international(item(1002, 3)), skus(heavy)), OrderErrorCode.SHIPPING_WEIGHT_NOT_SUPPORTED);
            rejected(() -> processor.quote(international(item(1001, 1), item(1002, 1)), skus(heaviest, heavy)), OrderErrorCode.SHIPPING_WEIGHT_NOT_SUPPORTED);
            assertNoShippingCall();
        }

        @Test
        @DisplayName("무게 또는 HS 가 없는 SKU 가 있고 배송할 수 없는 국가면 ORDER_013 이다 — 미입력보다 국가 사유가 앞서고, 견적은 부르지 않는다")
        void missingInfoToUnsupportedCountry() {
            shippingFeeQueryPort.destinationSupported(false);
            CatalogSku noWeight = withShippingInfo(sku(1001, 7001, "저고리", "S", 30_000), null, HS_CODE);

            rejected(() -> processor.quote(new OrderQuoteCommand(List.of(item(1001, 2)), "BR", "01310-100"), skus(noWeight)),
                OrderErrorCode.DESTINATION_NOT_SUPPORTED);

            assertThat(shippingFeeQueryPort.destinationChecks()).containsExactly(new DestinationCheck(krw(60_000), "BR", "01310-100"));
            assertThat(shippingFeeQueryPort.quoteCalls()).isEmpty();
        }

        @Test
        @DisplayName("무게 또는 HS 가 없는 SKU 가 있고 배송할 수 있는 국가면 ORDER_007 이다 — 미입력 줄을 모두 이름으로 알리고 견적은 부르지 않는다")
        void missingInfo() {
            CatalogSku noWeight = withShippingInfo(sku(1001, 7001, "저고리", "S", 30_000), null, HS_CODE);
            CatalogSku noHsCode = withShippingInfo(sku(1002, 7002, "치마", "", 40_000), 300, null);
            CatalogSku complete = sku(1003, 7003, "배자", "M", 50_000);

            OrderException exception = rejected(
                () -> processor.quote(international(item(1001, 1), item(1003, 1), item(1002, 1)), skus(noWeight, noHsCode, complete)),
                OrderErrorCode.INTERNATIONAL_SHIPPING_INFO_MISSING);

            assertThat(exception.getMessage()).isEqualTo("해외 배송 정보가 준비되지 않아 해외로 주문할 수 없는 상품이 있습니다: 저고리(S), 치마");
            assertThat(shippingFeeQueryPort.destinationChecks()).containsExactly(new DestinationCheck(krw(120_000), "US", null));
            assertThat(shippingFeeQueryPort.quoteCalls()).isEmpty();
        }

        @Test
        @DisplayName("배송 가능 국가 확인이 실패(503)하면 그대로 던진다 — 미입력 사유로 추측하지 않는다")
        void destinationCheckFailure() {
            shippingFeeQueryPort.willFailDestinationCheck(OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
            CatalogSku noHsCode = withShippingInfo(sku(1001, 7001, "저고리", "S", 30_000), 500, null);

            rejected(() -> processor.quote(international(item(1001, 1)), skus(noHsCode)), OrderErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
            assertThat(shippingFeeQueryPort.quoteCalls()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OrderErrorCode.class, names = {"SHIPPING_UNAVAILABLE", "DESTINATION_CURRENCY_NOT_SUPPORTED"})
        @DisplayName("배송 가능 국가 확인이 국가 판정 전 사유(정책 미설정 · 통화 불일치)로 거절되면 그 코드로 답한다 — 미입력(ORDER_007)으로 바꾸지 않는다")
        void destinationCheckRejectedBeforeCountry(OrderErrorCode rejection) {
            shippingFeeQueryPort.willFailDestinationCheck(rejection);
            CatalogSku noWeight = withShippingInfo(sku(1001, 7001, "저고리", "S", 30_000), null, HS_CODE);

            rejected(() -> processor.quote(international(item(1001, 1)), skus(noWeight)), rejection);
            assertThat(shippingFeeQueryPort.destinationChecks()).hasSize(1);
            assertThat(shippingFeeQueryPort.quoteCalls()).isEmpty();
        }

        @Test
        @DisplayName("무게 1 미만은 product-service 계약 위반이라 ORDER_002(503) 다 — 미입력 사유가 앞서고, 계약 위반은 무게 초과보다 앞선다. 로그에 우편번호 · 맞춤 입력 값이 없다")
        void weightContractViolation(CapturedOutput output) {
            CatalogSku zeroWeight = customOrder(withShippingInfo(sku(1001, 7001, "저고리", "S", 30_000), 0, HS_CODE));
            CatalogSku negativeWeight = withShippingInfo(sku(1002, 7002, "치마", "", 40_000), -1, HS_CODE);
            CatalogSku heavy = withShippingInfo(sku(1003, 7003, "궤짝", "", 30_000), Integer.MAX_VALUE, HS_CODE);
            CatalogSku noHsCode = withShippingInfo(sku(1004, 7004, "배자", "", 50_000), 500, null);
            Map<Long, CatalogSku> found = skus(zeroWeight, negativeWeight, heavy, noHsCode);

            rejected(() -> processor.quote(new OrderQuoteCommand(List.of(item(1001, 1, "chest", "가슴둘레비밀값")), "GB", "SW1A 1AA"), found),
                OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
            rejected(() -> processor.quote(international(item(1002, 1)), found), OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
            rejected(() -> processor.quote(international(item(1003, 2), item(1002, 1)), found), OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
            assertNoShippingCall();
            rejected(() -> processor.quote(international(item(1002, 1), item(1004, 1)), found), OrderErrorCode.INTERNATIONAL_SHIPPING_INFO_MISSING);

            assertThat(output).contains("product-service sku weight violates contract. skuId=1001, weightGrams=0");
            assertThat(output).doesNotContain("SW1A", "가슴둘레비밀값");
        }
    }
}
