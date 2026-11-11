package com.commercefoundry.domainlayer.sizechart.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartImageObjectQueryResult;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StoredObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SizeChartImageStorageAdapterTest {
    private static final String BASE = "https://cdn.example.com";
    private static final String KEY = "products/700/4611686018427387905.png";
    private static final String ETAG = "\"etag\"";
    private static final int MAX = 5 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private final ObjectStorageClient client = mock(ObjectStorageClient.class);
    private final SizeChartImageStorageAdapter adapter = new SizeChartImageStorageAdapter(client, MediaUrlPolicy.of(BASE));

    @Test
    void onlyOurPublicUrlsBecomeKeysAndNothingIsDownloaded() {
        when(client.publicUrlOf(KEY)).thenReturn(BASE + "/" + KEY);
        assertThat(adapter.managedKeyOf(BASE + "/" + KEY)).hasValue(KEY);
        assertThat(adapter.managedKeyOf("https://shop.example.org/" + KEY)).isEmpty();
        assertThat(adapter.managedKeyOf(BASE + "/products/700/%2e%2e/x.png")).isEmpty();
        assertThat(adapter.managedKeyOf(BASE + "/products/700/a%20b.png")).isEmpty();
        assertThat(adapter.managedKeyOf(null)).isEmpty();
        verify(client, never()).head(anyString());
    }

    @Test
    void readsWholeObjectBoundToHeadETagAndDetectsMagicBytes() {
        when(client.head(KEY)).thenReturn(Optional.of(new StoredObject(PNG.length, "image/jpeg", ETAG)));
        when(client.read(KEY, MAX, ETAG)).thenReturn(PNG);

        var result = adapter.read(KEY, MAX);

        // 저장된 Content-Type(image/jpeg)이 아니라 magic bytes 로 판별한다.
        assertThat(result).isInstanceOfSatisfying(SizeChartImageObjectQueryResult.Found.class, found -> {
            assertThat(found.contentType()).isEqualTo("image/png");
            assertThat(found.bytes()).isEqualTo(PNG);
        });
    }

    @Test
    void gifEmptyAndOversizedAreInvalidWithoutFullRead() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
        when(client.head(KEY)).thenReturn(Optional.of(new StoredObject(gif.length, "image/gif", ETAG)));
        when(client.read(KEY, MAX, ETAG)).thenReturn(gif);
        assertThat(adapter.read(KEY, MAX)).isInstanceOf(SizeChartImageObjectQueryResult.Invalid.class);

        when(client.head(KEY)).thenReturn(Optional.of(new StoredObject(MAX + 1L, "image/png", ETAG)), Optional.of(new StoredObject(0, "image/png", ETAG)));
        assertThat(adapter.read(KEY, MAX)).isInstanceOf(SizeChartImageObjectQueryResult.Invalid.class);
        assertThat(adapter.read(KEY, MAX)).isInstanceOf(SizeChartImageObjectQueryResult.Invalid.class);
    }

    @Test
    void missingChangedAndTooLargeReadsAreResultsButOutagesAre503() {
        when(client.head(KEY)).thenReturn(Optional.empty());
        assertThat(adapter.read(KEY, MAX)).isInstanceOf(SizeChartImageObjectQueryResult.Missing.class);

        when(client.head(KEY)).thenReturn(Optional.of(new StoredObject(PNG.length, "image/png", ETAG)));
        when(client.read(KEY, MAX, ETAG))
            .thenThrow(new StorageException(StorageException.Reason.CHANGED, StorageException.Operation.READ_RANGE))
            .thenThrow(new StorageException(StorageException.Reason.TOO_LARGE, StorageException.Operation.READ_RANGE))
            .thenReturn(new byte[] {1, 2})
            .thenThrow(new StorageException(StorageException.Reason.UNAVAILABLE, StorageException.Operation.READ_RANGE));
        assertThat(adapter.read(KEY, MAX)).isInstanceOf(SizeChartImageObjectQueryResult.Changed.class);
        assertThat(adapter.read(KEY, MAX)).isInstanceOf(SizeChartImageObjectQueryResult.Invalid.class);
        assertThat(adapter.read(KEY, MAX)).isInstanceOf(SizeChartImageObjectQueryResult.Changed.class); // HEAD 길이와 다르다
        assertThatThrownBy(() -> adapter.read(KEY, MAX)).isInstanceOfSatisfying(SizeChartDraftTransportException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.IMAGE_STORAGE_UNAVAILABLE);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).doesNotContain(KEY);
        });
        verify(client, never()).readRange(anyString(), anyInt(), anyString());
    }
}
