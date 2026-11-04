package com.commercefoundry.domainlayer.storeprofile.application.port.in;

import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.dto.response.StoreProfileAdminResponse;
import com.commercefoundry.domainlayer.storeprofile.application.command.StoreProfileSaveCommand;

public interface StoreProfileAdminUseCase {
    StoreProfileAdminResponse get();
    StoreProfileAdminResponse save(Long actorMemberId, StoreProfileSaveCommand command);
}
