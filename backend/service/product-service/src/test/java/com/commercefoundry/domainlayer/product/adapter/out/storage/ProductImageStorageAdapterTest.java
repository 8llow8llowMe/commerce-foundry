package com.commercefoundry.domainlayer.product.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductImageUploadTicket;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageFormat;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.PresignedUpload;
import com.commercefoundry.storage.client.StorageException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 스토리지 어댑터 — 서명 결과를 옮기고, storage-core 예외를 업무 오류로 바꾼다(타입이 밖으로 나가지 않는다). */
class ProductImageStorageAdapterTest {

    private static final ProductImageUploadKey KEY = new ProductImageUploadKey(100L, UUID.fromString("3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f"), ProductImageFormat.PNG);

    private final ObjectStorageClient client = mock(ObjectStorageClient.class);
    private final ProductImageStorageAdapter adapter = new ProductImageStorageAdapter(client);

    @Test
    @DisplayName("키 · 선언 형식의 Content-Type · 크기로 서명하고 결과를 그대로 옮긴다")
    void issuesUpload() {
        Instant expiresAt = Instant.parse("2026-11-01T03:05:00Z");
        given(client.presignPut(KEY.value(), "image/png", 2048L))
            .willReturn(new PresignedUpload("https://storage.example.com/b/" + KEY.value() + "?X-Amz-Signature=s", "PUT", Map.of("Content-Type", "image/png"), expiresAt));

        ProductImageUploadTicket ticket = adapter.issueUpload(KEY, 2048L);

        assertThat(ticket.uploadKey()).isEqualTo(KEY.value());
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/png");
        assertThat(ticket.expiresAt()).isEqualTo(expiresAt);
        assertThat(ticket.toString()).doesNotContain("Signature");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"DISABLED, PRODUCT_036", "NOT_FOUND, PRODUCT_032", "UNAVAILABLE, PRODUCT_035", "ACCESS_DENIED, PRODUCT_035", "REJECTED, PRODUCT_035", "CHANGED, PRODUCT_035"})
    @DisplayName("스토리지 예외는 업무 오류로 바뀐다 — 꺼짐 036 · 없음 032 · 그 밖 035")
    void translatesStorageErrors(StorageException.Reason reason, String code) {
        given(client.presignPut(KEY.value(), "image/png", 1L)).willThrow(new StorageException(reason, StorageException.Operation.PRESIGN_PUT));

        assertThatThrownBy(() -> adapter.issueUpload(KEY, 1L))
            .isInstanceOfSatisfying(ProductException.class, exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo(code));
        assertThat(ProductErrorCode.valueOf("IMAGE_UPLOAD_DISABLED").getHttpStatus().value()).isEqualTo(503);
    }
}
