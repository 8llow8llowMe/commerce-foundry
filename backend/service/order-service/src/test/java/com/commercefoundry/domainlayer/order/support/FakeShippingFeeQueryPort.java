package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.ShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.ArrayList;
import java.util.List;

/**
 * delivery-service 견적을 흉내 낸다. 호출마다 받은 조건을 남긴다 — 테스트가 "견적을 불렀는가 · 무엇을 실었는가" 를 본다.
 *
 * <p>견적 결과를 정하지 않으면 요청 국가 범위의 기본 견적(주문 통화)을 돌려준다 — 국내 3,000 · 해외 기본료 1,500 + 무게 요금 3,000.
 */
public class FakeShippingFeeQueryPort implements ShippingFeeQueryPort {

    public static final long DOMESTIC_FEE = 3_000;
    public static final long INTERNATIONAL_BASE_FEE = 1_500;
    public static final long INTERNATIONAL_WEIGHT_FEE = 3_000;

    private final List<ShippingFeeQuoteQuery> quoteCalls = new ArrayList<>();
    private final List<DestinationCheck> destinationChecks = new ArrayList<>();
    private ShippingFeeQuoteQueryResult quoteResult;
    private OrderErrorCode quoteRejection;
    private boolean destinationSupported = true;
    private OrderErrorCode destinationCheckFailure;

    /** 이후 견적이 이 결과를 돌려준다. */
    public FakeShippingFeeQueryPort willQuote(ShippingFeeQuoteQueryResult result) {
        this.quoteResult = result;
        return this;
    }

    /** 이후 견적이 이 코드로 거절한다 (adapter 가 번역한 뒤의 모습). */
    public FakeShippingFeeQueryPort willRejectQuote(OrderErrorCode errorCode) {
        this.quoteRejection = errorCode;
        return this;
    }

    public FakeShippingFeeQueryPort destinationSupported(boolean supported) {
        this.destinationSupported = supported;
        return this;
    }

    /** 이후 배송 가능 국가 확인이 이 코드로 실패한다 (예: 503 {@code ORDER_011}). */
    public FakeShippingFeeQueryPort willFailDestinationCheck(OrderErrorCode errorCode) {
        this.destinationCheckFailure = errorCode;
        return this;
    }

    @Override
    public ShippingFeeQuoteQueryResult quote(ShippingFeeQuoteQuery query) {
        quoteCalls.add(query);
        if (quoteRejection != null) {
            throw new OrderException(quoteRejection);
        }
        return quoteResult != null ? quoteResult : defaultQuote(query);
    }

    @Override
    public boolean isDestinationSupported(Money orderAmount, String countryCode, String postalCode) {
        destinationChecks.add(new DestinationCheck(orderAmount, countryCode, postalCode));
        if (destinationCheckFailure != null) {
            throw new OrderException(destinationCheckFailure);
        }
        return destinationSupported;
    }

    public List<ShippingFeeQuoteQuery> quoteCalls() {
        return quoteCalls;
    }

    public List<DestinationCheck> destinationChecks() {
        return destinationChecks;
    }

    public static ShippingFeeQuoteQueryResult domesticQuote(CurrencyCode currency, long fee) {
        return new ShippingFeeQuoteQueryResult(
            Money.of(fee, currency), Money.of(fee, currency), false, Money.zero(currency), null, null, 1L, ShippingScope.DOMESTIC, null, null);
    }

    private static ShippingFeeQuoteQueryResult defaultQuote(ShippingFeeQuoteQuery query) {
        CurrencyCode currency = query.orderAmount().currency();
        if (ShippingScope.ofCountry(query.countryCode()) == ShippingScope.DOMESTIC) {
            return domesticQuote(currency, DOMESTIC_FEE);
        }
        return new ShippingFeeQuoteQueryResult(
            Money.of(INTERNATIONAL_BASE_FEE + INTERNATIONAL_WEIGHT_FEE, currency), Money.of(INTERNATIONAL_BASE_FEE, currency), false, Money.zero(currency), null,
            null, 7L, ShippingScope.INTERNATIONAL, "북미", Money.of(INTERNATIONAL_WEIGHT_FEE, currency));
    }

    public record DestinationCheck(Money orderAmount, String countryCode, String postalCode) {
    }
}
