package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingFeeQuoteCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingFeeQuote;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 배송비 견적 — 배송 범위 결정 → 범위의 정책 조회 → 통화 확인 → 도메인 계산.
 *
 * <p>세 단계를 메서드로 나눠 둔다. 해외 배송비(#22)는 범위마다 계산이 달라지므로 범위 결정 뒤를 범위별 전략으로 바꿀 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
public class ShippingFeeQuoteProcessor {

    private final ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    /**
     * @throws DeliveryException 002 배송할 수 없는 국가 · 001 정책 없음 · 003 주문 금액 통화가 정책과 다름
     */
    public ShippingFeeQuote quote(ShippingFeeQuoteCommand command) {
        ShippingScope scope = resolveScope(command.countryCode());
        ShippingPolicy policy = findPolicy(scope);
        requirePolicyCurrency(policy, command.orderAmount());
        return policy.quote(command.orderAmount(), command.postalCode());
    }

    private static ShippingScope resolveScope(String countryCode) {
        return ShippingScope.ofCountry(countryCode).orElseThrow(() -> new DeliveryException(DeliveryErrorCode.DESTINATION_NOT_SUPPORTED));
    }

    /** 정책이 없으면 배송비를 0 으로 가정하지 않는다 — 무료로 주문이 만들어지지 않게 견적을 거절한다. */
    private ShippingPolicy findPolicy(ShippingScope scope) {
        return shippingPolicyRepositoryPort.findByScope(scope).orElseThrow(() -> new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_NOT_CONFIGURED));
    }

    /** 다른 통화끼리 비교하면 {@code Money} 가 {@link IllegalArgumentException}(500)을 낸다. 그 전에 요청 오류로 거절한다. */
    private static void requirePolicyCurrency(ShippingPolicy policy, Money orderAmount) {
        if (orderAmount.currency() != policy.currency()) {
            throw new DeliveryException(DeliveryErrorCode.CURRENCY_MISMATCH);
        }
    }
}
