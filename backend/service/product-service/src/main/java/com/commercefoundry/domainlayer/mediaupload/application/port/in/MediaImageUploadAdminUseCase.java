package com.commercefoundry.domainlayer.mediaupload.application.port.in;

import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaImageUploadResponse;

public interface MediaImageUploadAdminUseCase {
    MediaImageUploadResponse.Ticket issue(long actorId, String contentType, long contentLength);
    MediaImageUploadResponse.Confirmed confirm(long actorId, String uploadKey);
}
