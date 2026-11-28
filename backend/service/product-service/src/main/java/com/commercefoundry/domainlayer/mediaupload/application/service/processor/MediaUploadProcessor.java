package com.commercefoundry.domainlayer.mediaupload.application.service.processor;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.application.info.MediaUploadInfo;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.MediaStoragePort;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaUploadKey;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 이미지 · 영상 공용 업로드 발급 · 확정. 종류가 형식 · 크기 상한 · 키 확장자를 정한다. */
@Component
@RequiredArgsConstructor
public class MediaUploadProcessor {
    private final MediaStoragePort storage;

    public MediaUploadInfo.Ticket issue(long actorId, MediaKind kind, String contentType, long contentLength) {
        MediaFormat format = MediaFormat.ofContentType(kind, contentType)
            .orElseThrow(() -> new MediaUploadException(MediaUploadErrorCode.format(kind)));
        if (!kind.allowsSize(contentLength)) {
            throw new MediaUploadException(MediaUploadErrorCode.size(kind));
        }
        var ticket = storage.issue(actorId, new MediaUploadKey(UUID.randomUUID(), format), contentLength);
        return new MediaUploadInfo.Ticket(ticket.uploadKey(), ticket.uploadUrl(), ticket.method(), ticket.headers(), ticket.expiresAt());
    }

    public MediaUploadInfo.Confirmed confirm(long actorId, MediaUploadKey key) {
        var result = storage.confirm(actorId, key);
        return new MediaUploadInfo.Confirmed(result.publicUrl(), result.contentType(), result.contentLength());
    }

    public MediaUploadKey parseKey(MediaKind kind, String value) {
        try {
            return MediaUploadKey.parse(kind, value);
        } catch (IllegalArgumentException exception) {
            throw new MediaUploadException(MediaUploadErrorCode.INVALID_KEY);
        }
    }
}
