package com.commercefoundry.domainlayer.storefooter.application.port.in;

import com.commercefoundry.domainlayer.storefooter.adapter.in.web.dto.response.StoreFooterWebResponses;

public interface StoreFooterWebUseCase {
    StoreFooterWebResponses.Content published(String locale);
}
