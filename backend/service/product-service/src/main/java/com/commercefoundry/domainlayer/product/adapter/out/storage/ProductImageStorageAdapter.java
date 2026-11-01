package com.commercefoundry.domainlayer.product.adapter.out.storage;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductImageUploadTicket;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.PresignedUpload;
import com.commercefoundry.storage.client.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * storage-core {@link ObjectStorageClient} 로 상품 이미지 스토리지를 다룬다 (#9 F). storage-core 타입은 이 어댑터 밖으로 나가지 않는다.
 *
 * <p>오류 번역 — {@code DISABLED} → {@code PRODUCT_036}(503), {@code NOT_FOUND} → {@code PRODUCT_032}(400), 그 밖(연결 · 권한 · 거절 · 변경) →
 * {@code PRODUCT_035}(503). 로그에는 이유 · 동작만 남긴다 — 키 · 서명 주소 · 원인 메시지를 싣지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductImageStorageAdapter implements ProductImageStoragePort {

    private final ObjectStorageClient objectStorageClient;

    @Override
    public ProductImageUploadTicket issueUpload(ProductImageUploadKey key, long contentLength) {
        try {
            PresignedUpload upload = objectStorageClient.presignPut(key.value(), key.format().getContentType(), contentLength);
            return new ProductImageUploadTicket(key.value(), upload.uploadUrl(), upload.method(), upload.headers(), upload.expiresAt());
        } catch (StorageException exception) {
            throw translate(exception);
        }
    }

    static ProductException translate(StorageException exception) {
        return switch (exception.reason()) {
            case DISABLED -> new ProductException(ProductErrorCode.IMAGE_UPLOAD_DISABLED, exception);
            case NOT_FOUND -> new ProductException(ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND, exception);
            default -> {
                log.warn("product image storage failed. reason={}, operation={}", exception.reason(), exception.operation());
                yield new ProductException(ProductErrorCode.IMAGE_STORAGE_UNAVAILABLE, exception);
            }
        };
    }
}
