package com.commercefoundry.domainlayer.productdraft.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftImageObjectQueryResult;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StoredObject;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 관리 키 판정(I/O 없음) · HEAD 정규화 · ETag 조건 읽기와 magic bytes 판별 · 저장소 장애 503. */
class ProductDraftImageStorageAdapterTest {

    private static final String BASE = "https://cdn.example.com";
    private static final String KEY = "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/" + "a".repeat(64) + ".png";
    private static final String ETAG = "\"etag\"";
    private static final int MAX = 5 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private final ObjectStorageClient client = mock(ObjectStorageClient.class);
    private final ProductDraftImageStorageAdapter adapter = new ProductDraftImageStorageAdapter(client, MediaUrlPolicy.of(BASE));

    @Test
    @DisplayName("저장소가 만드는 공개 주소와 같은 주소만 키가 된다 — 외부 · 인코딩 · 경로 이동 주소는 비어 있고 HEAD 를 부르지 않는다")
    void managedKeys() {
        when(client.publicUrlOf(KEY)).thenReturn(BASE + "/" + KEY);
        assertThat(adapter.managedKeyOf(BASE + "/" + KEY)).hasValue(KEY);
        assertThat(adapter.managedKeyOf("https://shop.example.org/" + KEY)).isEmpty();
        assertThat(adapter.managedKeyOf(BASE + "/products/media/%2e%2e/x.png")).isEmpty();
        assertThat(adapter.managedKeyOf(BASE + "/products/media/a%20b.png")).isEmpty();
        assertThat(adapter.managedKeyOf(null)).isEmpty();

        when(client.publicUrlOf("products/media/x.png")).thenReturn("https://other.example.com/products/media/x.png");
        assertThat(adapter.managedKeyOf(BASE + "/products/media/x.png")).isEmpty();
        verify(client, never()).head(anyString());
    }

    @Test
    @DisplayName("HEAD 는 크기 · ETag 를 그대로, 형식은 이미지 MIME 으로 정규화하고 허용 형식 밖은 null 이다. 없으면 Missing")
    void head() {
        when(client.head(KEY)).thenReturn(Optional.of(new StoredObject(12, " IMAGE/PNG ", ETAG)));
        assertThat(adapter.head(KEY)).isEqualTo(new ProductDraftImageObjectQueryResult.Stored(12, "image/png", ETAG));

        when(client.head(KEY)).thenReturn(Optional.of(new StoredObject(12, "image/gif", ETAG)));
        assertThat(adapter.head(KEY)).isEqualTo(new ProductDraftImageObjectQueryResult.Stored(12, null, ETAG));

        when(client.head(KEY)).thenReturn(Optional.empty());
        assertThat(adapter.head(KEY)).isInstanceOf(ProductDraftImageObjectQueryResult.Missing.class);

        when(client.head(KEY)).thenThrow(new StorageException(StorageException.Reason.DISABLED, StorageException.Operation.HEAD));
        assertStorageUnavailable(() -> adapter.head(KEY));
    }

    @Test
    @DisplayName("ETag 조건으로 읽고 저장 Content-Type 이 아니라 magic bytes 로 형식을 정한다 — GIF · 빈 객체는 Invalid")
    void readDetectsMagicBytes() {
        when(client.read(KEY, MAX, ETAG)).thenReturn(PNG);
        assertThat(adapter.read(KEY, MAX, ETAG)).isInstanceOfSatisfying(ProductDraftImageObjectQueryResult.Read.class, read -> {
            assertThat(read.contentType()).isEqualTo("image/png");
            assertThat(read.bytes()).isEqualTo(PNG);
        });

        when(client.read(KEY, MAX, ETAG)).thenReturn(new byte[] {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0}, new byte[0]);
        assertThat(adapter.read(KEY, MAX, ETAG)).isInstanceOf(ProductDraftImageObjectQueryResult.Invalid.class);
        assertThat(adapter.read(KEY, MAX, ETAG)).isInstanceOf(ProductDraftImageObjectQueryResult.Invalid.class);
    }

    @Test
    @DisplayName("ETag 불일치는 Changed · 없음은 Missing · 상한 초과는 Invalid 결과이고, 그 밖의 저장소 오류는 503 PRODUCT_DRAFT_501 이다")
    void readFailures() {
        when(client.read(KEY, MAX, ETAG))
            .thenThrow(new StorageException(StorageException.Reason.CHANGED, StorageException.Operation.READ_RANGE))
            .thenThrow(new StorageException(StorageException.Reason.NOT_FOUND, StorageException.Operation.READ_RANGE))
            .thenThrow(new StorageException(StorageException.Reason.TOO_LARGE, StorageException.Operation.READ_RANGE))
            .thenThrow(new StorageException(StorageException.Reason.UNAVAILABLE, StorageException.Operation.READ_RANGE));

        assertThat(adapter.read(KEY, MAX, ETAG)).isInstanceOf(ProductDraftImageObjectQueryResult.Changed.class);
        assertThat(adapter.read(KEY, MAX, ETAG)).isInstanceOf(ProductDraftImageObjectQueryResult.Missing.class);
        assertThat(adapter.read(KEY, MAX, ETAG)).isInstanceOf(ProductDraftImageObjectQueryResult.Invalid.class);
        assertStorageUnavailable(() -> adapter.read(KEY, MAX, ETAG));
    }

    private static void assertStorageUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ProductDraftErrorCode.IMAGE_STORAGE_UNAVAILABLE);
            assertThat(exception.getCause()).isNull();
        });
    }
}
