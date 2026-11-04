package com.commercefoundry.domainlayer.storeprofile.application.info;

import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;

public record StoreProfileInfo(StoreProfile profile, Long version) {
}
