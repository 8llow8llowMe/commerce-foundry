package com.commercefoundry.domainlayer.mediaupload.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaUploadKey;
import com.commercefoundry.domainlayer.mediaupload.domain.model.Mp4Signature;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.PresignedUpload;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StoredObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class MediaStorageAdapterTest {
    private static final String BASE = "https://cdn.example.com/media/";
    private static final MediaUploadKey KEY = new MediaUploadKey(UUID.randomUUID(), MediaFormat.PNG);
    private static final MediaUploadKey VIDEO = new MediaUploadKey(UUID.randomUUID(), MediaFormat.MP4);
    private final ObjectStorageClient storage = mock(ObjectStorageClient.class);
    private final MediaStorageAdapter adapter = new MediaStorageAdapter(storage, MediaUrlPolicy.of(BASE.substring(0, BASE.length() - 1)));

    @BeforeEach
    void defaults() {
        when(storage.publicUrlOf(anyString())).thenAnswer(call -> BASE + call.getArgument(0));
        uploaded(KEY, 8, "image/png", "\"version-1\"");
        uploaded(VIDEO, 32, "video/mp4", "\"video-1\"");
    }

    private void uploaded(MediaUploadKey key, long size, String mime, String etag) {
        when(storage.head(key.sourceKey(7))).thenReturn(Optional.of(new StoredObject(size, mime, etag)));
        when(storage.readRange(eq(key.sourceKey(7)), eq(detectionLength(key)), anyString())).thenReturn(header(key.format()));
    }

    private static int detectionLength(MediaUploadKey key) {
        return key.kind() == MediaKind.VIDEO ? Mp4Signature.DETECTION_LENGTH : 12;
    }

    private static byte[] header(MediaFormat format) {
        return switch (format) {
            case PNG -> new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            case JPEG -> new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
            case WEBP -> new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
            case MP4 -> ftyp("isom", "isom", "iso2", "avc1", "mp41");
        };
    }

    /** ffmpeg 기본 출력과 같은 모양의 ftyp box(major · minor 0x200 · compatible). */
    private static byte[] ftyp(String major, String... compatible) {
        int size = 16 + compatible.length * 4;
        byte[] bytes = new byte[size];
        bytes[3] = (byte) size;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, bytes, 4, 4);
        System.arraycopy(major.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        bytes[14] = 2;
        for (int i = 0; i < compatible.length; i++) {
            System.arraycopy(compatible[i].getBytes(StandardCharsets.US_ASCII), 0, bytes, 16 + i * 4, 4);
        }
        return bytes;
    }

    private void fails(MediaUploadErrorCode code) {
        fails(KEY, code);
    }

    private void fails(MediaUploadKey key, MediaUploadErrorCode code) {
        assertThatThrownBy(() -> adapter.confirm(7, key)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verify(storage, never()).copy(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = MediaFormat.class, names = {"JPEG", "PNG", "WEBP"})
    void validatesMagicAndPassesOpaqueEtagToRangeAndCopy(MediaFormat format) {
        var key = new MediaUploadKey(KEY.id(), format);
        uploaded(key, MediaKind.IMAGE_MAX_BYTES, " " + format.getContentType().toUpperCase(Locale.ROOT) + " ", "\"opaque-multipart-2\"");
        var result = adapter.confirm(7, key);
        assertThat(result.contentType()).isEqualTo(format.getContentType());
        assertThat(result.contentLength()).isEqualTo(MediaKind.IMAGE_MAX_BYTES);
        verify(storage).readRange(key.sourceKey(7), 12, "\"opaque-multipart-2\"");
        verify(storage).copy(eq(key.sourceKey(7)), startsWith("products/media/7/" + KEY.id() + "/"),
            eq(format.getContentType()), eq(MediaStorageAdapter.CACHE_CONTROL), eq("\"opaque-multipart-2\""));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void retryAndConcurrentConfirmKeepUrlAndChangedVersionGetsNewUrl() throws Exception {
        String first = adapter.confirm(7, KEY).publicUrl();
        assertThat(adapter.confirm(7, KEY).publicUrl()).isEqualTo(first);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> adapter.confirm(7, KEY).publicUrl());
            var b = executor.submit(() -> adapter.confirm(7, KEY).publicUrl());
            assertThat(a.get()).isEqualTo(first);
            assertThat(b.get()).isEqualTo(first);
        }
        uploaded(KEY, 8, "image/png", "\"version-2\"");
        assertThat(adapter.confirm(7, KEY).publicUrl()).isNotEqualTo(first);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void lostCopyResponseCanRetryWithoutDeletingSource() {
        doThrow(new StorageException(StorageException.Reason.UNAVAILABLE, StorageException.Operation.COPY)).doNothing()
            .when(storage).copy(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> adapter.confirm(7, KEY)).isInstanceOf(MediaUploadException.class);
        assertThat(adapter.confirm(7, KEY).publicUrl()).contains("products/media/7/");
        verify(storage, times(2)).copy(anyString(), anyString(), eq("image/png"), eq(MediaStorageAdapter.CACHE_CONTROL), eq("\"version-1\""));
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 10485761})
    void rejectsSize(long size) {
        uploaded(KEY, size, "image/png", "\"etag\"");
        fails(MediaUploadErrorCode.SIZE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "W/\"weak\"", "unquoted", " \"padded\"", "\"\"", "\"invalid space\""})
    void rejectsMissingOrWeakEtag(String etag) {
        uploaded(KEY, 8, "image/png", etag);
        fails(MediaUploadErrorCode.STORAGE);
        verify(storage, never()).readRange(anyString(), anyInt(), anyString());
    }

    @Test
    void rejectsMissingEtag() {
        uploaded(KEY, 8, "image/png", null);
        fails(MediaUploadErrorCode.STORAGE);
    }

    @Test
    void mimeAndMagicMustMatchExtension() {
        uploaded(KEY, 8, "image/jpeg", "\"etag\"");
        fails(MediaUploadErrorCode.FORMAT);
        uploaded(KEY, 8, "image/png", "\"etag\"");
        when(storage.readRange(KEY.sourceKey(7), 12, "\"etag\"")).thenReturn(header(MediaFormat.JPEG));
        fails(MediaUploadErrorCode.FORMAT);
    }

    @Test
    void imageKeyRejectsVideoMimeAndMagic() {
        uploaded(KEY, 8, "video/mp4", "\"etag\"");
        fails(MediaUploadErrorCode.FORMAT);
        uploaded(KEY, 8, "image/png", "\"etag\"");
        when(storage.readRange(KEY.sourceKey(7), 12, "\"etag\"")).thenReturn(header(MediaFormat.MP4));
        fails(MediaUploadErrorCode.FORMAT);
    }

    @Test
    void rejectsNonImageMagic() {
        when(storage.readRange(KEY.sourceKey(7), 12, "\"version-1\"")).thenReturn("<svg></svg>".getBytes(StandardCharsets.UTF_8));
        fails(MediaUploadErrorCode.FORMAT);
    }

    @Test
    void urlPolicyAndLengthFailBeforeCopy() {
        when(storage.publicUrlOf(anyString())).thenReturn("https://other.example.com/x.png");
        fails(MediaUploadErrorCode.STORAGE);
        when(storage.publicUrlOf(anyString())).thenReturn(BASE + "a".repeat(500));
        fails(MediaUploadErrorCode.STORAGE);
    }

    @Test
    void ownerNamespaceAndMissingAreSameNotFound() {
        when(storage.head(KEY.sourceKey(8))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.confirm(8, KEY)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.NOT_FOUND));
        verify(storage, never()).head(KEY.sourceKey(7));
    }

    @ParameterizedTest
    @EnumSource(StorageException.Reason.class)
    void safelyTranslatesStorageFailures(StorageException.Reason reason) {
        when(storage.head(KEY.sourceKey(7))).thenThrow(new StorageException(reason, StorageException.Operation.HEAD,
            new RuntimeException("secret-url-source-key")));
        assertThatThrownBy(() -> adapter.confirm(7, KEY)).isInstanceOfSatisfying(MediaUploadException.class, exception -> {
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).doesNotContain("secret", KEY.value());
            assertThat(exception.getErrorCode()).isEqualTo(switch (reason) {
                case NOT_FOUND -> MediaUploadErrorCode.NOT_FOUND;
                case CHANGED -> MediaUploadErrorCode.CHANGED;
                case DISABLED -> MediaUploadErrorCode.DISABLED;
                default -> MediaUploadErrorCode.STORAGE;
            });
        });
    }

    @Test
    void rangeAndCopyRacesAreConflict() {
        when(storage.readRange(KEY.sourceKey(7), 12, "\"version-1\""))
            .thenThrow(new StorageException(StorageException.Reason.CHANGED, StorageException.Operation.READ_RANGE));
        fails(MediaUploadErrorCode.CHANGED);
        doReturn(header(MediaFormat.PNG)).when(storage).readRange(KEY.sourceKey(7), 12, "\"version-1\"");
        doThrow(new StorageException(StorageException.Reason.CHANGED, StorageException.Operation.COPY))
            .when(storage).copy(anyString(), anyString(), anyString(), anyString(), eq("\"version-1\""));
        assertThatThrownBy(() -> adapter.confirm(7, KEY)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.CHANGED));
    }

    // ----- 영상 (#796) -----

    @Test
    void videoIssueSignsMp4UnderOwnerNamespace() {
        when(storage.presignPut(anyString(), anyString(), eq(MediaKind.VIDEO_MAX_BYTES)))
            .thenReturn(new PresignedUpload("https://storage.example.com/signed", "PUT", Map.of("Content-Type", "video/mp4"), Instant.EPOCH));
        var ticket = adapter.issue(7, VIDEO, MediaKind.VIDEO_MAX_BYTES);
        assertThat(ticket.uploadKey()).isEqualTo(VIDEO.id() + ".mp4");
        verify(storage).presignPut("uploads/display-media/7/" + VIDEO.id() + ".mp4", "video/mp4", MediaKind.VIDEO_MAX_BYTES);
    }

    @Test
    void videoConfirmReadsFtypWithOpaqueEtagAndCopiesImmutableMp4() {
        uploaded(VIDEO, MediaKind.VIDEO_MAX_BYTES, " VIDEO/MP4 ", "\"opaque-multipart-9\"");
        var result = adapter.confirm(7, VIDEO);
        assertThat(result.contentType()).isEqualTo("video/mp4");
        assertThat(result.contentLength()).isEqualTo(MediaKind.VIDEO_MAX_BYTES);
        assertThat(result.publicUrl()).startsWith(BASE + "products/media/7/" + VIDEO.id() + "/").endsWith(".mp4");
        verify(storage).readRange(VIDEO.sourceKey(7), Mp4Signature.DETECTION_LENGTH, "\"opaque-multipart-9\"");
        verify(storage).copy(eq(VIDEO.sourceKey(7)), startsWith("products/media/7/" + VIDEO.id() + "/"),
            eq("video/mp4"), eq(MediaStorageAdapter.CACHE_CONTROL), eq("\"opaque-multipart-9\""));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void videoRetryKeepsUrlAndChangedEtagGetsNewUrl() {
        String first = adapter.confirm(7, VIDEO).publicUrl();
        assertThat(adapter.confirm(7, VIDEO).publicUrl()).isEqualTo(first);
        uploaded(VIDEO, 32, "video/mp4", "\"video-2\"");
        assertThat(adapter.confirm(7, VIDEO).publicUrl()).isNotEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 104857601})
    void videoRejectsSizeOutsideOneByteTo100MiB(long size) {
        uploaded(VIDEO, size, "video/mp4", "\"etag\"");
        fails(VIDEO, MediaUploadErrorCode.VIDEO_SIZE);
        verify(storage, never()).readRange(anyString(), anyInt(), anyString());
    }

    @Test
    void videoAcceptsImageSizedAndLargerThanImageLimit() {
        uploaded(VIDEO, MediaKind.IMAGE_MAX_BYTES + 1, "video/mp4", "\"etag\"");
        assertThat(adapter.confirm(7, VIDEO).contentLength()).isEqualTo(MediaKind.IMAGE_MAX_BYTES + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "video/webm", "video/quicktime", "application/octet-stream", "video/mp4; codecs=avc1"})
    void videoHeadMimeMustBeMp4(String mime) {
        uploaded(VIDEO, 32, mime, "\"etag\"");
        fails(VIDEO, MediaUploadErrorCode.VIDEO_FORMAT);
        verify(storage, never()).readRange(anyString(), anyInt(), anyString());
    }

    @Test
    void videoHeadMimeMissingIsFormat() {
        uploaded(VIDEO, 32, null, "\"etag\"");
        fails(VIDEO, MediaUploadErrorCode.VIDEO_FORMAT);
    }

    @Test
    void videoMagicMustBeMp4Ftyp() {
        when(storage.readRange(VIDEO.sourceKey(7), Mp4Signature.DETECTION_LENGTH, "\"video-1\"")).thenReturn(header(MediaFormat.PNG));
        fails(VIDEO, MediaUploadErrorCode.VIDEO_FORMAT);
        when(storage.readRange(VIDEO.sourceKey(7), Mp4Signature.DETECTION_LENGTH, "\"video-1\"")).thenReturn(ftyp("qt  ", "qt  "));
        fails(VIDEO, MediaUploadErrorCode.VIDEO_FORMAT);
        when(storage.readRange(VIDEO.sourceKey(7), Mp4Signature.DETECTION_LENGTH, "\"video-1\""))
            .thenReturn("<html><script>".getBytes(StandardCharsets.UTF_8));
        fails(VIDEO, MediaUploadErrorCode.VIDEO_FORMAT);
    }

    @Test
    void videoWeakEtagAndUrlPolicyAreVideoStorage() {
        uploaded(VIDEO, 32, "video/mp4", "W/\"weak\"");
        fails(VIDEO, MediaUploadErrorCode.VIDEO_STORAGE);
        uploaded(VIDEO, 32, "video/mp4", "\"video-1\"");
        when(storage.publicUrlOf(anyString())).thenReturn("https://other.example.com/x.mp4");
        fails(VIDEO, MediaUploadErrorCode.VIDEO_STORAGE);
    }

    @Test
    void videoOwnerNamespaceAndMissingAreSameVideoNotFound() {
        when(storage.head(VIDEO.sourceKey(8))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.confirm(8, VIDEO)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.VIDEO_NOT_FOUND));
        verify(storage, never()).head(VIDEO.sourceKey(7));
    }

    @ParameterizedTest
    @EnumSource(StorageException.Reason.class)
    void videoSafelyTranslatesStorageFailures(StorageException.Reason reason) {
        when(storage.head(VIDEO.sourceKey(7))).thenThrow(new StorageException(reason, StorageException.Operation.HEAD,
            new RuntimeException("secret-url-source-key")));
        assertThatThrownBy(() -> adapter.confirm(7, VIDEO)).isInstanceOfSatisfying(MediaUploadException.class, exception -> {
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).doesNotContain("secret", VIDEO.value());
            assertThat(exception.getErrorCode()).isEqualTo(switch (reason) {
                case NOT_FOUND -> MediaUploadErrorCode.VIDEO_NOT_FOUND;
                case CHANGED -> MediaUploadErrorCode.CHANGED;
                case DISABLED -> MediaUploadErrorCode.VIDEO_DISABLED;
                default -> MediaUploadErrorCode.VIDEO_STORAGE;
            });
        });
    }

    @Test
    void videoRangeAndCopyRacesAreConflict() {
        when(storage.readRange(VIDEO.sourceKey(7), Mp4Signature.DETECTION_LENGTH, "\"video-1\""))
            .thenThrow(new StorageException(StorageException.Reason.CHANGED, StorageException.Operation.READ_RANGE));
        fails(VIDEO, MediaUploadErrorCode.CHANGED);
        doReturn(header(MediaFormat.MP4)).when(storage).readRange(VIDEO.sourceKey(7), Mp4Signature.DETECTION_LENGTH, "\"video-1\"");
        doThrow(new StorageException(StorageException.Reason.CHANGED, StorageException.Operation.COPY))
            .when(storage).copy(anyString(), anyString(), anyString(), anyString(), eq("\"video-1\""));
        assertThatThrownBy(() -> adapter.confirm(7, VIDEO)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.CHANGED));
    }
}
