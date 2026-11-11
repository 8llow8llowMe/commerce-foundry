package com.commercefoundry.domainlayer.productdraft.adapter.out.storage;

import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftImageStoragePort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftImageObjectQueryResult;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StoredObject;
import com.commercefoundry.storage.image.ImageFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * storage-core 로 상품 초안 이미지를 확인 · 읽는다 (#359 D3). 판정 흐름은 사이즈표 {@code SizeChartImageStorageAdapter} 와 같고, 결과 · 오류 타입만 이 컨텍스트 것이다 —
 * 두 컨텍스트의 오류 코드 · 기능 플래그가 따로 움직여 공용으로 빼지 않았다. magic bytes 판별은 storage-core {@link ImageFormat} 하나를 같이 쓴다.
 * 로그에는 이유 · 동작만 남긴다 — 키 · 주소 · ETag · 바이트 · 원인 메시지를 싣지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDraftImageStorageAdapter implements ProductDraftImageStoragePort {

    private final ObjectStorageClient objectStorageClient;
    private final MediaUrlPolicy mediaUrlPolicy;

    @Override
    public Optional<String> managedKeyOf(String publicUrl) {
        Optional<String> key = mediaUrlPolicy.keyOf(publicUrl);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            // 저장소가 만드는 공개 주소와 정확히 같아야 우리 키다(공개 주소 설정 불일치 · 인코딩 차이를 거른다).
            return publicUrl.equals(objectStorageClient.publicUrlOf(key.get())) ? key : Optional.empty();
        } catch (StorageException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public ProductDraftImageObjectQueryResult head(String key) {
        try {
            Optional<StoredObject> head = objectStorageClient.head(key);
            if (head.isEmpty()) {
                return new ProductDraftImageObjectQueryResult.Missing();
            }
            StoredObject stored = head.get();
            // 저장 Content-Type 은 업로드 확정(#9 미디어 업로드)이 magic bytes 판별 결과로 고정한 값이다. 바이트는 생성 작업이 읽을 때 다시 판별한다.
            String contentType = ImageFormat.ofContentType(stored.contentType()).map(ImageFormat::contentType).orElse(null);
            return new ProductDraftImageObjectQueryResult.Stored(stored.contentLength(), contentType, stored.eTag());
        } catch (StorageException exception) {
            if (exception.reason() == StorageException.Reason.NOT_FOUND) {
                return new ProductDraftImageObjectQueryResult.Missing();
            }
            throw unavailable(exception);
        }
    }

    @Override
    public ProductDraftImageObjectQueryResult read(String key, int maxBytes, String expectedETag) {
        try {
            byte[] bytes = objectStorageClient.read(key, maxBytes, expectedETag);
            // 확장자 · 저장된 Content-Type 은 믿지 않는다. 맨 앞 magic bytes 만 본다(GIF · SVG · HTML 거절).
            return ImageFormat.detect(bytes)
                .<ProductDraftImageObjectQueryResult>map(format -> new ProductDraftImageObjectQueryResult.Read(bytes, format.contentType()))
                .orElseGet(ProductDraftImageObjectQueryResult.Invalid::new);
        } catch (StorageException exception) {
            return switch (exception.reason()) {
                case NOT_FOUND -> new ProductDraftImageObjectQueryResult.Missing();
                case CHANGED -> new ProductDraftImageObjectQueryResult.Changed();
                case TOO_LARGE -> new ProductDraftImageObjectQueryResult.Invalid();
                default -> throw unavailable(exception);
            };
        }
    }

    private static ProductDraftException unavailable(StorageException exception) {
        log.warn("product draft image storage failed. reason={}, operation={}", exception.reason(), exception.operation());
        return new ProductDraftException(ProductDraftErrorCode.IMAGE_STORAGE_UNAVAILABLE);
    }
}
