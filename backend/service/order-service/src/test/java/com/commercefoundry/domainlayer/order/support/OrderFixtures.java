package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.catalog.application.port.out.query.SkuQueryResult;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.order.domain.model.ShippingFeeQuote;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;

/** 주문 테스트 값 — 모두 가짜다(실제 상품 · 사람 · 주소가 아니다). 상품 아이디는 SKU + 7000, 이름은 "상품{SKU}" · "옵션{SKU}" 이다. */
public final class OrderFixtures {

    public static final String KR = "KR";
    public static final String US = "US";
    public static final String KR_POSTAL_CODE = "63100";
    public static final String HS_CODE = "6211420000";
    public static final int WEIGHT_GRAMS = 500;

    private OrderFixtures() {
    }

    public static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    /** 구매 가능 · 제한 없음 · 일반 상품 · 무게 500g · HS 코드 있음. */
    public static SkuQueryResult onSale(long skuId, long price) {
        return new SkuQueryResult(skuId, skuId + 7000, "상품" + skuId, "옵션" + skuId, krw(price), true, false, null, false, WEIGHT_GRAMS, HS_CODE);
    }

    /** 무게 · HS 코드 미입력 SKU. */
    public static SkuQueryResult withoutShippingInfo(long skuId, long price) {
        return new SkuQueryResult(skuId, skuId + 7000, "상품" + skuId, "옵션" + skuId, krw(price), true, false, null, false, null, null);
    }

    /** 가짜 배송지 — KR 이면 우편번호 63100, 해외면 우편번호 없음. */
    public static ShippingAddress address(String countryCode) {
        String postalCode = KR.equals(countryCode) ? KR_POSTAL_CODE : null;
        return new ShippingAddress("테스트수령인", "010-0000-0001", countryCode, postalCode, "테스트시 테스트로 1", "101호", null);
    }

    public static OrderItemCommand line(long skuId, int quantity) {
        return new OrderItemCommand(skuId, quantity, OrderCustomOptions.empty());
    }

    public static OrderItemCommand line(long skuId, int quantity, String fieldKey, String value) {
        return new OrderItemCommand(skuId, quantity, OrderCustomOptions.of(List.of(new OrderCustomOption(fieldKey, value))));
    }

    /** {@link #onSale} 와 같은 SKU 값의 주문서 줄. 맞춤 입력이 있으면 맞춤 제작 상품이다. */
    public static OrderQuoteLine quoteLine(long skuId, long price, int quantity, OrderCustomOptions customOptions) {
        return OrderQuoteLine.of(skuId, skuId + 7000, "상품" + skuId, "옵션" + skuId, krw(price), quantity, customOptions, !customOptions.isEmpty(),
            WEIGHT_GRAMS, HS_CODE);
    }

    public static OrderQuoteLine quoteLine(long skuId, long price, int quantity) {
        return quoteLine(skuId, price, quantity, OrderCustomOptions.empty());
    }

    /** 국내 주문서 — 기본 배송비 3,000, 5만 원 이상 무료, 도서산간 아님, 정책 version 3. */
    public static OrderQuote domesticQuote(OrderQuoteLine... lines) {
        Money itemsAmount = List.of(lines).stream().map(OrderQuoteLine::lineAmount).reduce(krw(0), Money::plus);
        boolean free = itemsAmount.amount() >= 50_000;
        ShippingFeeQuote fee = new ShippingFeeQuote(free ? krw(0) : krw(3_000), krw(3_000), free, krw(0), null, krw(50_000), 3L, ShippingScope.DOMESTIC,
            null, null, null);
        return new OrderQuote(CurrencyCode.KRW, List.of(lines), itemsAmount, fee, itemsAmount.plus(fee.shippingFee()), KR);
    }

    /** 해외(US) 주문서 — 기본료 5,000 + 무게 요금 20,000, 국가 그룹 "북미", 총 무게 = Σ 500g × 수량. */
    public static OrderQuote internationalQuote(OrderQuoteLine... lines) {
        Money itemsAmount = List.of(lines).stream().map(OrderQuoteLine::lineAmount).reduce(krw(0), Money::plus);
        int weight = List.of(lines).stream().mapToInt(line -> line.weightGrams() * line.quantity()).sum();
        ShippingFeeQuote fee = new ShippingFeeQuote(krw(25_000), krw(5_000), false, krw(0), null, null, 4L, ShippingScope.INTERNATIONAL, "북미", krw(20_000), weight);
        return new OrderQuote(CurrencyCode.KRW, List.of(lines), itemsAmount, fee, itemsAmount.plus(fee.shippingFee()), US);
    }
}
