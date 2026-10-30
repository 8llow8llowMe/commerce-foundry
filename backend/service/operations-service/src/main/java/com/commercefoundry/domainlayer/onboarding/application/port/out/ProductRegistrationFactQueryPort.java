package com.commercefoundry.domainlayer.onboarding.application.port.out;

import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ProductRegistrationFactQueryResult;

public interface ProductRegistrationFactQueryPort {
    ProductRegistrationFactQueryResult findProductRegistrationFact(long memberId);
}
