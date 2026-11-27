package com.commercefoundry.domainlayer.storenavigation.application.port.in;

import com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.dto.response.StoreNavigationAdminResponses;
import com.commercefoundry.domainlayer.storenavigation.application.command.StoreNavigationCommands;

public interface StoreNavigationAdminUseCase {
    StoreNavigationAdminResponses.Detail detail();
    StoreNavigationAdminResponses.Detail save(long actorMemberId, StoreNavigationCommands.SaveDraft command);
}
