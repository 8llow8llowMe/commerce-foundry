package com.commercefoundry.domainlayer.storenavigation.application.port.in;

import com.commercefoundry.domainlayer.storenavigation.adapter.in.web.dto.response.StoreNavigationWebResponses;

public interface StoreNavigationWebUseCase {
    StoreNavigationWebResponses.Content published(String locale);
}
