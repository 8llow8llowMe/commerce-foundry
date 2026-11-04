package com.commercefoundry.domainlayer.storeprofile.application.port.in;

import com.commercefoundry.domainlayer.storeprofile.adapter.in.web.dto.response.StoreProfileWebResponse;

public interface StoreProfileWebUseCase {
    StoreProfileWebResponse get();

}
