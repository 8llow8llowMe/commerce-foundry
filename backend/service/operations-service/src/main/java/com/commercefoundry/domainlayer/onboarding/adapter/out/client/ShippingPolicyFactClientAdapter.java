package com.commercefoundry.domainlayer.onboarding.adapter.out.client;

import com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.ShippingPolicyFactClient;
import com.commercefoundry.domainlayer.onboarding.adapter.out.client.support.ShippingPolicyFactInternalResponseSupport;
import com.commercefoundry.domainlayer.onboarding.application.port.out.ShippingPolicyFactQueryPort;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ShippingPolicyFactQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShippingPolicyFactClientAdapter implements ShippingPolicyFactQueryPort {

    private final ShippingPolicyFactClient shippingPolicyFactClient;
    private final ShippingPolicyFactInternalResponseSupport shippingPolicyFactInternalResponseSupport;

    @Override
    public ShippingPolicyFactQueryResult findShippingPolicyFact(long memberId) {
        return shippingPolicyFactInternalResponseSupport.toQueryResult(
            shippingPolicyFactInternalResponseSupport.call(() -> shippingPolicyFactClient.getShippingPolicyFact(memberId))
        );
    }
}
