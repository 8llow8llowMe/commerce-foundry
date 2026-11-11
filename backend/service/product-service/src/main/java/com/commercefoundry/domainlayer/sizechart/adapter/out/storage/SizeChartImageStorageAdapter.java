package com.commercefoundry.domainlayer.sizechart.adapter.out.storage;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartImageStoragePort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartImageObjectQueryResult;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.image.ImageFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * storage-core 로 사이즈표 초안 이미지를 읽는다. 외부 주소는 키로 바꾸지 않으므로 내려받을 수 없다.
 * 로그에는 이유 · 동작만 남긴다 — 키 · 주소 · 바이트 · 원인 메시지를 싣지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SizeChartImageStorageAdapter implements SizeChartImageStoragePort {

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
    public SizeChartImageObjectQueryResult read(String key, int maxBytes) {
        try {
            var head = objectStorageClient.head(key);
            if (head.isEmpty()) {
                return new SizeChartImageObjectQueryResult.Missing();
            }
            long length = head.get().contentLength();
            if (length <= 0 || length > maxBytes) {
                return new SizeChartImageObjectQueryResult.Invalid();
            }
            byte[] bytes = objectStorageClient.read(key, maxBytes, head.get().eTag());
            if (bytes.length != length) {
                return new SizeChartImageObjectQueryResult.Changed();
            }
            // 확장자 · 저장된 Content-Type 은 믿지 않는다. 맨 앞 magic bytes 만 본다(GIF · SVG · HTML 거절).
            return ImageFormat.detect(bytes)
                .<SizeChartImageObjectQueryResult>map(format -> new SizeChartImageObjectQueryResult.Found(bytes, format.contentType()))
                .orElseGet(SizeChartImageObjectQueryResult.Invalid::new);
        } catch (StorageException exception) {
            return switch (exception.reason()) {
                case NOT_FOUND -> new SizeChartImageObjectQueryResult.Missing();
                case CHANGED -> new SizeChartImageObjectQueryResult.Changed();
                case TOO_LARGE -> new SizeChartImageObjectQueryResult.Invalid();
                default -> {
                    log.warn("size chart image storage failed. reason={}, operation={}", exception.reason(), exception.operation());
                    throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.IMAGE_STORAGE_UNAVAILABLE, exception);
                }
            };
        }
    }
}
