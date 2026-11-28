package com.commercefoundry.domainlayer.mediaupload.application.port.in;

import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaVideoUploadResponse;

public interface MediaVideoUploadAdminUseCase {
    MediaVideoUploadResponse.Ticket issue(long actorId, String contentType, long contentLength);
    MediaVideoUploadResponse.Confirmed confirm(long actorId, String uploadKey);
}
