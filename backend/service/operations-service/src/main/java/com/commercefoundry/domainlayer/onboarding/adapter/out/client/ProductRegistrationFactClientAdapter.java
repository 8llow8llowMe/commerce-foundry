package com.commercefoundry.domainlayer.onboarding.adapter.out.client;

import com.commercefoundry.domainlayer.onboarding.adapter.out.client.feign.ProductRegistrationFactClient;
import com.commercefoundry.domainlayer.onboarding.adapter.out.client.support.ProductRegistrationFactInternalResponseSupport;
import com.commercefoundry.domainlayer.onboarding.application.port.out.ProductRegistrationFactQueryPort;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ProductRegistrationFactQueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductRegistrationFactClientAdapter implements ProductRegistrationFactQueryPort {
    private final ProductRegistrationFactClient client;
    private final ProductRegistrationFactInternalResponseSupport support;

    @Override
    public ProductRegistrationFactQueryResult findProductRegistrationFact(long memberId) {
        return support.toQueryResult(support.call(() -> client.getProductRegistrationFact(memberId)));
    }
}
