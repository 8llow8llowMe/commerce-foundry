package com.commercefoundry.domainlayer.storeprofile.application.port.out.query;

import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;

public record StoreProfileQueryResult(StoreProfile profile, Long version) {
}
