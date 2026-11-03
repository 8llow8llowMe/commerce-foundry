package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductImagesUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Validate every input in a short DB snapshot before any external copy. */
@Component
@RequiredArgsConstructor
public class ProductImagesPrepareProcessor {
    private final ProductRepositoryPort productRepositoryPort;
    private final ProductImageRepositoryPort productImageRepositoryPort;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void prepare(ProductImagesUpdateCommand command) {
        var product = productRepositoryPort.findAllByIdsIncludingHidden(List.of(command.productId())).stream().findFirst()
            .filter(p -> !p.isDeleted()).orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        if (product.version() == null || product.version() != command.version()) {
            throw new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        }
        validate(command, productImageRepositoryPort.findAllByProductId(command.productId()));
    }

    public void validate(ProductImagesUpdateCommand command, List<ProductImage> existing) {
        var inputs = command.images();
        var ids = new HashSet<Long>();
        var keys = new HashSet<String>();
        if (inputs == null || inputs.size() > 8) invalid();
        int thumbnails = 0;
        for (var input : inputs) {
            if (input == null || input.imageType() == null || input.imageType() == ImageType.VIDEO
                || (input.imageId() == null) == (input.uploadKey() == null)) invalid();
            if (input.imageType() == ImageType.THUMBNAIL && ++thumbnails > 1) invalid();
            if (input.altText() != null && (input.altText().length() > 200 || !input.altText().matches(ProductValidationMessage.PLAIN_TEXT))) invalid();
            if (input.imageId() != null) {
                if (!ids.add(input.imageId()) || existing.stream().noneMatch(i -> i.id() == input.imageId() && i.imageType() != ImageType.VIDEO)) invalid();
            } else {
                if (!keys.add(input.uploadKey())) invalid();
                ProductImageUploadKey.parse(input.uploadKey()).filter(k -> k.isOwnedBy(command.actorMemberId()))
                    .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND));
            }
        }
    }

    private static void invalid() { throw new ProductException(ProductErrorCode.IMAGE_CONFIGURATION_INVALID); }
}
