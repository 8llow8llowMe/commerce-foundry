package com.commercefoundry.domainlayer.shippingfee.application.command;

import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 배송비 견적 요청.
 *
 * @param orderAmount 할인 후 상품 합계 (배송비 제외). 무료배송 기준과 비교만 한다 — 계산 주체는 order-service 다
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 */
public record ShippingFeeQuoteCommand(Money orderAmount, String countryCode, PostalCode postalCode) {

    public ShippingFeeQuoteCommand {
        Objects.requireNonNull(orderAmount, "orderAmount");
        Objects.requireNonNull(countryCode, "countryCode");
        Objects.requireNonNull(postalCode, "postalCode");
    }
}
