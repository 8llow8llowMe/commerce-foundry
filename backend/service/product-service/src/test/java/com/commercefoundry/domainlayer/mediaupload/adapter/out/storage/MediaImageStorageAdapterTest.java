package com.commercefoundry.domainlayer.mediaupload.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadErrorCode;
import com.commercefoundry.domainlayer.mediaupload.application.exception.MediaUploadException;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageUploadKey;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StoredObject;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class MediaImageStorageAdapterTest {
    private static final String BASE = "https://cdn.example.com/media/";
    private static final MediaImageUploadKey KEY = new MediaImageUploadKey(UUID.randomUUID(), MediaImageFormat.PNG);
    private final ObjectStorageClient storage = mock(ObjectStorageClient.class);
    private final MediaImageStorageAdapter adapter = new MediaImageStorageAdapter(storage, MediaUrlPolicy.of(BASE.substring(0, BASE.length() - 1)));

    @BeforeEach
    void defaults() {
        when(storage.publicUrlOf(anyString())).thenAnswer(call -> BASE + call.getArgument(0));
        uploaded(KEY, 8, "image/png", "\"version-1\"");
    }

    private void uploaded(MediaImageUploadKey key, long size, String mime, String etag) {
        when(storage.head(key.sourceKey(7))).thenReturn(Optional.of(new StoredObject(size, mime, etag)));
        when(storage.readRange(eq(key.sourceKey(7)), eq(12), anyString())).thenReturn(header(key.format()));
    }

    private static byte[] header(MediaImageFormat format) {
        return switch (format) {
            case PNG -> new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            case JPEG -> new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
            case WEBP -> new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        };
    }

    private void fails(MediaUploadErrorCode code) {
        assertThatThrownBy(() -> adapter.confirm(7, KEY)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verify(storage, never()).copy(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(MediaImageFormat.class)
    void validatesMagicAndPassesOpaqueEtagToRangeAndCopy(MediaImageFormat format) {
        var key = new MediaImageUploadKey(KEY.id(), format);
        uploaded(key, MediaImageFormat.MAX_BYTES, " " + format.getContentType().toUpperCase(java.util.Locale.ROOT) + " ", "\"opaque-multipart-2\"");
        var result = adapter.confirm(7, key);
        assertThat(result.contentType()).isEqualTo(format.getContentType());
        assertThat(result.contentLength()).isEqualTo(MediaImageFormat.MAX_BYTES);
        verify(storage).readRange(key.sourceKey(7), 12, "\"opaque-multipart-2\"");
        verify(storage).copy(eq(key.sourceKey(7)), startsWith("products/media/7/" + KEY.id() + "/"),
            eq(format.getContentType()), eq(MediaImageStorageAdapter.CACHE_CONTROL), eq("\"opaque-multipart-2\""));
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
        verify(storage, times(2)).copy(anyString(), anyString(), eq("image/png"), eq(MediaImageStorageAdapter.CACHE_CONTROL), eq("\"version-1\""));
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
        when(storage.readRange(KEY.sourceKey(7), 12, "\"etag\"")).thenReturn(header(MediaImageFormat.JPEG));
        fails(MediaUploadErrorCode.FORMAT);
    }

    @Test
    void rejectsNonImageMagic() {
        when(storage.readRange(KEY.sourceKey(7), 12, "\"version-1\"")).thenReturn("<svg></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        doReturn(header(MediaImageFormat.PNG)).when(storage).readRange(KEY.sourceKey(7), 12, "\"version-1\"");
        doThrow(new StorageException(StorageException.Reason.CHANGED, StorageException.Operation.COPY))
            .when(storage).copy(anyString(), anyString(), anyString(), anyString(), eq("\"version-1\""));
        assertThatThrownBy(() -> adapter.confirm(7, KEY)).isInstanceOfSatisfying(MediaUploadException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MediaUploadErrorCode.CHANGED));
    }
}
