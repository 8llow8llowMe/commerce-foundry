package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaImageUploadResponse;
import com.commercefoundry.domainlayer.mediaupload.application.info.MediaImageUploadInfo;
import org.springframework.stereotype.Component;

@Component
public class MediaImageUploadAdminPresenter {
    public MediaImageUploadResponse.Ticket toResponse(MediaImageUploadInfo.Ticket info) {
        return new MediaImageUploadResponse.Ticket(info.uploadKey(), info.uploadUrl(), info.method(), info.headers(), info.expiresAt());
    }

    public MediaImageUploadResponse.Confirmed toResponse(MediaImageUploadInfo.Confirmed info) {
        return new MediaImageUploadResponse.Confirmed(info.publicUrl(), info.contentType(), info.contentLength());
    }
}
