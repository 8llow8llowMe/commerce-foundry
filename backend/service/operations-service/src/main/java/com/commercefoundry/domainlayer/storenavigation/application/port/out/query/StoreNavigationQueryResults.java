package com.commercefoundry.domainlayer.storenavigation.application.port.out.query;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigation;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;

public final class StoreNavigationQueryResults {
    private StoreNavigationQueryResults() {
    }

    public record Detail(StoreNavigation navigation, StoreNavigationRevision revision) {
    }
}
