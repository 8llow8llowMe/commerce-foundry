package com.commercefoundry.domainlayer.storeprofile.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.storeprofile.adapter.in.web.dto.response.StoreProfileWebResponse;
import com.commercefoundry.domainlayer.storeprofile.application.info.StoreProfileInfo;
import org.springframework.stereotype.Component;

@Component
public class StoreProfileWebPresenter {

    public StoreProfileWebResponse toResponse(StoreProfileInfo info) {
        var profile = info.profile();
        return new StoreProfileWebResponse(
            profile.storeName(), profile.companyName(), profile.representativeName(), profile.countryCode(),
            profile.businessRegistrationNumber(), profile.mailOrderRegistrationNumber(), profile.address(),
            profile.customerServiceEmail(), profile.customerServicePhone(), profile.customerServiceHours()
        );
    }
}
