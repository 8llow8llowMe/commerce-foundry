package com.commercefoundry.domainlayer.storeprofile.application.port.out;

import com.commercefoundry.domainlayer.storeprofile.application.port.out.query.StoreProfileQueryResult;
import com.commercefoundry.domainlayer.storeprofile.domain.model.StoreProfile;
import java.util.Optional;

public interface StoreProfilePort {
    Optional<StoreProfileQueryResult> find();
    StoreProfileQueryResult replace(Long expectedVersion, StoreProfile profile);
}
