package com.commercefoundry.domainlayer.mediaupload.application.port.out;

import com.commercefoundry.domainlayer.mediaupload.application.port.out.query.MediaImageUploadQueryResult;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageUploadKey;

public interface MediaImageStoragePort {
    MediaImageUploadQueryResult.Ticket issue(long actorId, MediaImageUploadKey key, long contentLength);
    MediaImageUploadQueryResult.Confirmed confirm(long actorId, MediaImageUploadKey key);
}
