package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.util.Optional;

/**
 * 국내 · 해외 정책 교체가 같이 쓰는 통화 규칙 (#70).
 *
 * <p>정책 통화가 견적 통화이고, 주문 통화가 다르면 견적을 거절한다(003). 그래서 저장된 정책의 통화를 PUT 으로 바꾸면 그 순간부터 지금까지
 * 받던 통화의 주문이 모두 막힌다 — 어드민 실수 한 번이 장애가 되지 않게 교체에서는 통화를 바꾸지 못하게 한다. 통화를 바꾸려면 운영 절차(DDL)로 한다.
 * 통화를 KRW 로 고정하지 않는다 — 처음 만들 때는 어떤 통화든 받는다(엔진 재사용).
 */
final class ShippingPolicyCurrencies {

    private ShippingPolicyCurrencies() {
    }

    /**
     * @param currentCurrency 저장된 정책의 통화. 정책이 아직 없으면 빈 값 — 처음 만들 때는 통화를 고를 수 있다
     * @throws DeliveryException 012 저장된 정책과 통화가 다를 때
     */
    static void requireUnchanged(Optional<CurrencyCode> currentCurrency, CurrencyCode requestedCurrency) {
        currentCurrency.filter(current -> current != requestedCurrency).ifPresent(current -> {
            throw new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_CURRENCY_CHANGE_NOT_ALLOWED, current);
        });
    }
}
