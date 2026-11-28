package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaImageUploadResponse;
import com.commercefoundry.domainlayer.mediaupload.application.info.MediaUploadInfo;
import org.springframework.stereotype.Component;

@Component
public class MediaImageUploadAdminPresenter {
    public MediaImageUploadResponse.Ticket toResponse(MediaUploadInfo.Ticket info) {
        return new MediaImageUploadResponse.Ticket(info.uploadKey(), info.uploadUrl(), info.method(), info.headers(), info.expiresAt());
    }

    public MediaImageUploadResponse.Confirmed toResponse(MediaUploadInfo.Confirmed info) {
        return new MediaImageUploadResponse.Confirmed(info.publicUrl(), info.contentType(), info.contentLength());
    }
}
