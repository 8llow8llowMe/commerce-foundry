package com.commercefoundry.domainlayer.storenavigation.application.info;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigation;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;

public final class StoreNavigationInfos {
    private StoreNavigationInfos() {
    }

    public record Detail(StoreNavigation navigation, StoreNavigationRevision revision) {
    }
}
