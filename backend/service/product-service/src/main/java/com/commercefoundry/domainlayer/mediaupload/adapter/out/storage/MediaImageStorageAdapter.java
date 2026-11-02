package com.commercefoundry.domainlayer.mediaupload.adapter.out.storage;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.query.MediaImageUploadQueryResult;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.MediaImageStoragePort;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageUploadKey;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.image.ImageFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaImageStorageAdapter implements MediaImageStoragePort {
    public static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    private final ObjectStorageClient storage;
    private final MediaUrlPolicy mediaUrlPolicy;

    @Override
    public MediaImageUploadQueryResult.Ticket issue(long actorId, MediaImageUploadKey key, long contentLength) {
        try {
            var ticket = storage.presignPut(key.sourceKey(actorId), key.format().getContentType(), contentLength);
            return new MediaImageUploadQueryResult.Ticket(key.value(), ticket.uploadUrl(), ticket.method(), ticket.headers(), ticket.expiresAt());
        } catch (StorageException exception) {
            throw translate(exception);
        }
    }

    @Override
    public MediaImageUploadQueryResult.Confirmed confirm(long actorId, MediaImageUploadKey key) {
        try {
            String sourceKey = key.sourceKey(actorId);
            var source = storage.head(sourceKey).orElseThrow(() -> new MediaUploadException(MediaUploadErrorCode.NOT_FOUND));
            if (source.contentLength() < 1 || source.contentLength() > MediaImageFormat.MAX_BYTES) {
                throw new MediaUploadException(MediaUploadErrorCode.SIZE);
            }
            String eTag = source.eTag();
            if (!isStrongETag(eTag)) {
                throw new MediaUploadException(MediaUploadErrorCode.STORAGE);
            }
            if (MediaImageFormat.ofContentType(source.contentType()).filter(key.format()::equals).isEmpty()) {
                throw new MediaUploadException(MediaUploadErrorCode.FORMAT);
            }
            var detected = ImageFormat.detect(storage.readRange(sourceKey, ImageFormat.DETECTION_LENGTH, eTag))
                .orElseThrow(() -> new MediaUploadException(MediaUploadErrorCode.FORMAT));
            if (!detected.contentType().equals(key.format().getContentType())) {
                throw new MediaUploadException(MediaUploadErrorCode.FORMAT);
            }
            String target = "products/media/" + actorId + "/" + key.id() + "/" + namespaceHash(eTag) + "." + key.format().getExtension();
            String publicUrl = storage.publicUrlOf(target);
            if (publicUrl == null || publicUrl.length() > 500 || !mediaUrlPolicy.isAllowed(publicUrl)) {
                throw new MediaUploadException(MediaUploadErrorCode.STORAGE);
            }
            storage.copy(sourceKey, target, detected.contentType(), CACHE_CONTROL, eTag);
            // Keep source for retry after lost COPY responses. External uploads/ lifecycle owns its expiry.
            return new MediaImageUploadQueryResult.Confirmed(publicUrl, detected.contentType(), source.contentLength());
        } catch (StorageException exception) {
            throw translate(exception);
        }
    }

    private static boolean isStrongETag(String value) {
        if (value == null || value.length() < 3 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return false;
        }
        return value.substring(1, value.length() - 1).chars()
            .allMatch(character -> character == 0x21 || character >= 0x23 && character <= 0x7e || character >= 0x80 && character <= 0xff);
    }

    private static String namespaceHash(String eTag) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(eTag.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static MediaUploadException translate(StorageException exception) {
        // Never retain SDK causes: they can include URLs, credentials and object keys.
        return new MediaUploadException(switch (exception.reason()) {
            case NOT_FOUND -> MediaUploadErrorCode.NOT_FOUND;
            case CHANGED -> MediaUploadErrorCode.CHANGED;
            case DISABLED -> MediaUploadErrorCode.DISABLED;
            default -> MediaUploadErrorCode.STORAGE;
        });
    }
}
