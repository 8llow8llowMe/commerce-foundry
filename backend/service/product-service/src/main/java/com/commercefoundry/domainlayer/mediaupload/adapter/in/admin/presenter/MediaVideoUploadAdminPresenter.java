package com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaVideoUploadResponse;
import com.commercefoundry.domainlayer.mediaupload.application.info.MediaUploadInfo;
import org.springframework.stereotype.Component;

@Component
public class MediaVideoUploadAdminPresenter {
    public MediaVideoUploadResponse.Ticket toResponse(MediaUploadInfo.Ticket info) {
        return new MediaVideoUploadResponse.Ticket(info.uploadKey(), info.uploadUrl(), info.method(), info.headers(), info.expiresAt());
    }

    public MediaVideoUploadResponse.Confirmed toResponse(MediaUploadInfo.Confirmed info) {
        return new MediaVideoUploadResponse.Confirmed(info.publicUrl(), info.contentType(), info.contentLength());
    }
}
