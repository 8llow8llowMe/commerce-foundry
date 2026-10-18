package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.InternationalShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingFeeQuote;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 배송비 견적 — 배송 범위 결정 → 범위의 정책 조회 → 통화 확인 → 범위별 도메인 계산.
 *
 * <p>정책 통화가 견적 통화다. 주문 통화가 정책 통화와 다르면 환산하지 않고 거절한다(003) — 환율 기준(시점 · 출처)을 delivery 가 정하면
 * 주문 금액을 다시 만들 수 없다 (#70, {@code order-flow-guide.md} §2). 영문 스토어(USD) 주문의 국내(KR) 배송은 국내 정책 통화(KRW)와 달라 003 이다.
 */
@Component
@RequiredArgsConstructor
public class ShippingFeeQuoteProcessor {

    private final ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;
    private final InternationalShippingPolicyRepositoryPort internationalShippingPolicyRepositoryPort;
    private final StoreProperties storeProperties;

    /**
     * @throws DeliveryException 002 배송할 수 없는 국가(해외 배송 꺼짐 · 국가 그룹에 없음) · 001 정책 없음 · 003 주문 금액 통화가 정책과 다름 ·
     *     011 국가 그룹의 최대 무게 초과
     */
    public ShippingFeeQuote quote(ShippingFeeQuoteCommand command) {
        ShippingScope scope = resolveScope(command.countryCode());
        return switch (scope) {
            case DOMESTIC -> quoteDomestic(command);
            case INTERNATIONAL -> quoteInternational(command);
        };
    }

    private static ShippingScope resolveScope(String countryCode) {
        return ShippingScope.ofCountry(countryCode).orElseThrow(() -> new DeliveryException(DeliveryErrorCode.DESTINATION_NOT_SUPPORTED));
    }

    private ShippingFeeQuote quoteDomestic(ShippingFeeQuoteCommand command) {
        ShippingPolicy policy = shippingPolicyRepositoryPort.findDomesticPolicy().orElseThrow(ShippingFeeQuoteProcessor::notConfigured);
        requirePolicyCurrency(policy.currency(), command.orderAmount());
        return policy.quote(command.orderAmount(), command.postalCode());
    }

    /** 해외 배송이 꺼진 스토어는 정책을 읽지 않고 거절한다 — 정책을 미리 만들어 둔 상태에서도 주문이 열리지 않게. */
    private ShippingFeeQuote quoteInternational(ShippingFeeQuoteCommand command) {
        if (!storeProperties.features().internationalShipping()) {
            throw new DeliveryException(DeliveryErrorCode.DESTINATION_NOT_SUPPORTED);
        }
        InternationalShippingPolicy policy = internationalShippingPolicyRepositoryPort.findPolicy().orElseThrow(ShippingFeeQuoteProcessor::notConfigured);
        requirePolicyCurrency(policy.currency(), command.orderAmount());
        ShippingZone zone = policy.findZone(command.countryCode()).orElseThrow(() -> new DeliveryException(DeliveryErrorCode.DESTINATION_NOT_SUPPORTED));
        int weightGrams = command.weightGrams();
        if (zone.findRate(weightGrams).isEmpty()) {
            throw new DeliveryException(DeliveryErrorCode.WEIGHT_NOT_SUPPORTED, weightGrams, zone.maxWeightGrams());
        }
        return policy.quote(command.orderAmount(), command.countryCode(), weightGrams);
    }

    /** 정책이 없으면 배송비를 0 으로 가정하지 않는다 — 무료로 주문이 만들어지지 않게 견적을 거절한다. */
    private static DeliveryException notConfigured() {
        return new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_NOT_CONFIGURED);
    }

    /** 다른 통화끼리 비교하면 {@code Money} 가 {@link IllegalArgumentException}(500)을 낸다. 그 전에 요청 오류로 거절한다. */
    private static void requirePolicyCurrency(CurrencyCode policyCurrency, Money orderAmount) {
        if (orderAmount.currency() != policyCurrency) {
            throw new DeliveryException(DeliveryErrorCode.CURRENCY_MISMATCH);
        }
    }
}
