package com.commercefoundry.domainlayer.mediaupload.adapter.out.storage;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.MediaStoragePort;
import com.commercefoundry.domainlayer.mediaupload.application.port.out.query.MediaUploadQueryResult;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaUploadKey;
import com.commercefoundry.domainlayer.mediaupload.domain.model.Mp4Signature;
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

/**
 * 이미지(#508) · 영상(#796) 공용 업로드 서명과 검증 · 공개 복사. 종류마다 형식 · 크기 상한 · 시그니처만 다르고 나머지 규칙은 같다 —
 * HEAD 크기 · MIME · strong ETag 검증, ETag 조건부 range · copy, 공개 키의 SHA-256(ETag) segment, immutable cache, MediaUrlPolicy 확인.
 */
@Component
@RequiredArgsConstructor
public class MediaStorageAdapter implements MediaStoragePort {
    public static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    private final ObjectStorageClient storage;
    private final MediaUrlPolicy mediaUrlPolicy;

    @Override
    public MediaUploadQueryResult.Ticket issue(long actorId, MediaUploadKey key, long contentLength) {
        try {
            var ticket = storage.presignPut(key.sourceKey(actorId), key.format().getContentType(), contentLength);
            return new MediaUploadQueryResult.Ticket(key.value(), ticket.uploadUrl(), ticket.method(), ticket.headers(), ticket.expiresAt());
        } catch (StorageException exception) {
            throw translate(key.kind(), exception);
        }
    }

    @Override
    public MediaUploadQueryResult.Confirmed confirm(long actorId, MediaUploadKey key) {
        MediaKind kind = key.kind();
        MediaFormat format = key.format();
        try {
            String sourceKey = key.sourceKey(actorId);
            var source = storage.head(sourceKey).orElseThrow(() -> new MediaUploadException(MediaUploadErrorCode.notFound(kind)));
            if (!kind.allowsSize(source.contentLength())) {
                throw new MediaUploadException(MediaUploadErrorCode.size(kind));
            }
            String eTag = source.eTag();
            if (!isStrongETag(eTag)) {
                throw new MediaUploadException(MediaUploadErrorCode.storage(kind));
            }
            if (MediaFormat.ofContentType(kind, source.contentType()).filter(format::equals).isEmpty()) {
                throw new MediaUploadException(MediaUploadErrorCode.format(kind));
            }
            if (!matchesSignature(format, storage.readRange(sourceKey, detectionLength(format), eTag))) {
                throw new MediaUploadException(MediaUploadErrorCode.format(kind));
            }
            String target = "products/media/" + actorId + "/" + key.id() + "/" + namespaceHash(eTag) + "." + format.getExtension();
            String publicUrl = storage.publicUrlOf(target);
            if (publicUrl == null || publicUrl.length() > 500 || !mediaUrlPolicy.isAllowed(publicUrl)) {
                throw new MediaUploadException(MediaUploadErrorCode.storage(kind));
            }
            storage.copy(sourceKey, target, format.getContentType(), CACHE_CONTROL, eTag);
            // Keep source for retry after lost COPY responses. External uploads/ lifecycle owns its expiry.
            return new MediaUploadQueryResult.Confirmed(publicUrl, format.getContentType(), source.contentLength());
        } catch (StorageException exception) {
            throw translate(kind, exception);
        }
    }

    private static int detectionLength(MediaFormat format) {
        return switch (format) {
            case JPEG, PNG, WEBP -> ImageFormat.DETECTION_LENGTH;
            case MP4 -> Mp4Signature.DETECTION_LENGTH;
        };
    }

    /** 맨 앞 바이트로 판별한 형식이 키 형식과 같아야 한다. 이미지는 storage-core 판별, 영상은 ISO BMFF ftyp 판별이다. */
    private static boolean matchesSignature(MediaFormat format, byte[] head) {
        return switch (format) {
            case JPEG, PNG, WEBP -> ImageFormat.detect(head).filter(detected -> detected.contentType().equals(format.getContentType())).isPresent();
            case MP4 -> Mp4Signature.matches(head);
        };
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

    private static MediaUploadException translate(MediaKind kind, StorageException exception) {
        // Never retain SDK causes: they can include URLs, credentials and object keys.
        return new MediaUploadException(switch (exception.reason()) {
            case NOT_FOUND -> MediaUploadErrorCode.notFound(kind);
            case CHANGED -> MediaUploadErrorCode.CHANGED;
            case DISABLED -> MediaUploadErrorCode.disabled(kind);
            default -> MediaUploadErrorCode.storage(kind);
        });
    }
}
