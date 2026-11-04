package com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.dto.response.StoreProfileAdminResponse;
import com.commercefoundry.domainlayer.storeprofile.application.info.StoreProfileInfo;
import org.springframework.stereotype.Component;

@Component
public class StoreProfileAdminPresenter {

    public StoreProfileAdminResponse toResponse(StoreProfileInfo info) {
        var profile = info.profile();
        return new StoreProfileAdminResponse(
            profile.storeName(), profile.companyName(), profile.representativeName(), profile.countryCode(),
            profile.businessRegistrationNumber(), profile.mailOrderRegistrationNumber(), profile.address(),
            profile.customerServiceEmail(), profile.customerServicePhone(), profile.customerServiceHours(), info.version()
        );
    }
}
