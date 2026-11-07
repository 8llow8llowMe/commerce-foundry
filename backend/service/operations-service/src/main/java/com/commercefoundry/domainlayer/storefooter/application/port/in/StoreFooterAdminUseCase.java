package com.commercefoundry.domainlayer.storefooter.application.port.in;

import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.dto.response.StoreFooterAdminResponses;
import com.commercefoundry.domainlayer.storefooter.application.command.StoreFooterCommands;
import com.commercefoundry.persistence.dto.PageResponse;

public interface StoreFooterAdminUseCase {
    StoreFooterAdminResponses.Detail detail();
    StoreFooterAdminResponses.Detail save(long actorMemberId, StoreFooterCommands.SaveDraft command);
    StoreFooterAdminResponses.Detail publish(long actorMemberId, StoreFooterCommands.Publish command);
    PageResponse<StoreFooterAdminResponses.Revision> revisions(int page, int size);
}
