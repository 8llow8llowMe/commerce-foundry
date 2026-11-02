package com.commercefoundry.domainlayer.mediaupload.application.service.processor;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.application.info.MediaImageUploadInfo;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.MediaImageStoragePort;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageUploadKey;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaImageUploadProcessor {
    private final MediaImageStoragePort storage;

    public MediaImageUploadInfo.Ticket issue(long actorId, String contentType, long contentLength) {
        MediaImageFormat format = MediaImageFormat.ofContentType(contentType)
            .orElseThrow(() -> new MediaUploadException(MediaUploadErrorCode.FORMAT));
        if (contentLength < 1 || contentLength > MediaImageFormat.MAX_BYTES) {
            throw new MediaUploadException(MediaUploadErrorCode.SIZE);
        }
        var ticket = storage.issue(actorId, new MediaImageUploadKey(UUID.randomUUID(), format), contentLength);
        return new MediaImageUploadInfo.Ticket(ticket.uploadKey(), ticket.uploadUrl(), ticket.method(), ticket.headers(), ticket.expiresAt());
    }

    public MediaImageUploadInfo.Confirmed confirm(long actorId, MediaImageUploadKey key) {
        var result = storage.confirm(actorId, key);
        return new MediaImageUploadInfo.Confirmed(result.publicUrl(), result.contentType(), result.contentLength());
    }

    public MediaImageUploadKey parseKey(String value) {
        try {
            return MediaImageUploadKey.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new MediaUploadException(MediaUploadErrorCode.INVALID_KEY);
        }
    }
}
