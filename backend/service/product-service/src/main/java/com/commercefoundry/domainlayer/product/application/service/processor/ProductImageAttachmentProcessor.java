package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 외부 I/O 는 DB 트랜잭션 전에 끝낸다. 원본은 만료까지 남겨 실패 후 재시도할 수 있다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductImageAttachmentProcessor {

    private final ProductImageStoragePort productImageStoragePort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public List<ProductImage> attach(long productId, ProductCreateCommand command) {
        var inputs = command.images();
        if (inputs.size() > 8 || inputs.stream().anyMatch(image -> image.imageType() == null || image.imageType() == ImageType.VIDEO)
            || inputs.stream().filter(image -> image.imageType() == ImageType.THUMBNAIL).count() > 1
            || new HashSet<>(inputs.stream().map(ProductCreateCommand.Image::uploadKey).toList()).size() != inputs.size()) {
            throw new ProductException(ProductErrorCode.IMAGE_CONFIGURATION_INVALID);
        }
        // 모든 소유 확인을 외부 호출 전에 끝낸다. 키 · 어드민 ID 는 오류 응답에 싣지 않는다.
        var keys = inputs.stream().map(image -> ProductImageUploadKey.parse(image.uploadKey())
            .filter(key -> key.isOwnedBy(command.createdByMemberId()))
            .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND))).toList();
        List<ProductImage> images = new ArrayList<>();
        List<String> attemptedKeys = new ArrayList<>();
        try {
            for (int index = 0; index < inputs.size(); index++) {
                var input = inputs.get(index);
                var key = keys.get(index);
                long imageId = snowflakeIdGenerator.generateId();
                String target = "products/" + productId + "/" + imageId + "." + key.format().getExtension();
                // 복사 응답 유실도 보상할 수 있도록 시도 전에 기록한다. 목적지는 이 요청만 소유한다.
                attemptedKeys.add(target);
                String url = productImageStoragePort.attach(key, target);
                images.add(new ProductImage(imageId, productId, input.imageType(), url, null, null, input.altText(),
                    input.imageType() == ImageType.THUMBNAIL ? 0 : index + 1, null, target));
            }
            return List.copyOf(images);
        } catch (RuntimeException exception) {
            cleanup(attemptedKeys);
            throw exception;
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
