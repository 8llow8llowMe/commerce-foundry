package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageFormat;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageManagedSource;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 외부 I/O 는 DB 트랜잭션 전에 끝낸다. 원본은 만료까지 남겨 실패 후 재시도할 수 있다.
 *
 * <p>원본은 두 가지다 — 어드민이 올린 임시 업로드 키(#9 F2, 요청한 어드민 소유만)와 고정한 상품 미디어 원본(#359 C1, AI 상품 초안 적용 — 서버가 초안에서 만든다).
 * 둘 다 목적지 {@code products/{productId}/{imageId}.{ext}} 로 복사하고, 구성 판정 · 순서 · 보상 삭제는 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductImageAttachmentProcessor {

    private final ProductImageStoragePort productImageStoragePort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public List<ProductImage> attach(long productId, ProductCreateCommand command) {
        return attach(productId, command.createdByMemberId(), command.images().stream()
            .map(i -> new Input(i.uploadKey(), i.managedSource(), i.imageType(), i.altText())).toList());
    }

    public List<ProductImage> attach(long productId, long actorMemberId, List<Input> inputs) {
        if (inputs.size() > 8 || inputs.stream().anyMatch(image -> image.imageType() == null || image.imageType() == ImageType.VIDEO)
            || inputs.stream().filter(image -> image.imageType() == ImageType.THUMBNAIL).count() > 1
            || new HashSet<>(inputs.stream().map(Input::sourceKey).toList()).size() != inputs.size()) {
            throw new ProductException(ProductErrorCode.IMAGE_CONFIGURATION_INVALID);
        }
        // 모든 소유 확인을 외부 호출 전에 끝낸다. 키 · 어드민 ID 는 오류 응답에 싣지 않는다. 관리 원본은 초안 저장 때 검증 · 고정했다.
        List<ProductImageUploadKey> keys = inputs.stream().map(image -> image.managedSource() != null ? null
            : ProductImageUploadKey.parse(image.uploadKey())
                .filter(key -> key.isOwnedBy(actorMemberId))
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND))).toList();
        List<ProductImage> images = new ArrayList<>();
        List<String> attemptedKeys = new ArrayList<>();
        try {
            for (int index = 0; index < inputs.size(); index++) {
                var input = inputs.get(index);
                var key = keys.get(index);
                ProductImageFormat format = key == null ? input.managedSource().format() : key.format();
                long imageId = snowflakeIdGenerator.generateId();
                String target = "products/" + productId + "/" + imageId + "." + format.getExtension();
                // 복사 응답 유실도 보상할 수 있도록 시도 전에 기록한다. 목적지는 이 요청만 소유한다.
                attemptedKeys.add(target);
                String url = key == null
                    ? productImageStoragePort.attachManaged(input.managedSource(), target)
                    : productImageStoragePort.attach(key, target);
                images.add(new ProductImage(imageId, productId, input.imageType(), url, null, null, input.altText(),
                    input.imageType() == ImageType.THUMBNAIL ? 0 : index + 1, null, target));
            }
            return List.copyOf(images);
        } catch (RuntimeException exception) {
            cleanup(attemptedKeys);
            throw exception;
        }
    }

    /** @param managedSource 고정한 상품 미디어 원본(#359 C1). 있으면 {@code uploadKey} 는 쓰지 않는다 */
    public record Input(String uploadKey, ProductImageManagedSource managedSource, ImageType imageType, String altText) {

        public Input(String uploadKey, ImageType imageType, String altText) {
            this(uploadKey, null, imageType, altText);
        }

        /** 같은 원본을 두 번 붙이지 않게 비교하는 값. */
        String sourceKey() {
            return managedSource != null ? managedSource.key() : uploadKey;
        }
    }

    public void cleanup(List<String> keys) {
        for (String key : keys) {
            try {
                productImageStoragePort.delete(key);
            } catch (RuntimeException exception) {
                // 원래 업무 오류를 보존하고 운영자가 products/ 고아 객체를 정리하게 한다. 키 · 원인 메시지는 남기지 않는다.
                log.warn("product image compensation failed; orphan reconciliation required");
            }
        }
    }
}
