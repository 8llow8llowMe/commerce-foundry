package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductImagesUpdateCommand;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ProductImagesUpdateProcessor {
    private final ProductRepositoryPort productRepositoryPort;
    private final ProductImageRepositoryPort productImageRepositoryPort;
    private final ProductImagesPrepareProcessor productImagesPrepareProcessor;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductAdminDetailInfo update(ProductImagesUpdateCommand command, List<ProductImage> copied) {
        productRepositoryPort.bumpVersion(command.productId(), command.version());
        var existing = productImageRepositoryPort.findAllByProductId(command.productId());
        productImagesPrepareProcessor.validate(command, existing);
        var all = new ArrayList<ProductImage>();
        int nextCopy = 0;
        for (int index = 0; index < command.images().size(); index++) {
            var input = command.images().get(index);
            var source = input.imageId() == null ? copied.get(nextCopy++)
                : existing.stream().filter(i -> i.id() == input.imageId()).findFirst().orElseThrow();
            all.add(new ProductImage(source.id(), command.productId(), input.imageType(), source.url(), source.width(), source.height(),
                input.altText(), input.imageType() == ImageType.THUMBNAIL ? 0 : index + 1, null, source.storageKey()));
        }
        var retainedIds = command.images().stream().map(ProductImagesUpdateCommand.Image::imageId).filter(java.util.Objects::nonNull).toList();
        productImageRepositoryPort.replaceStillImages(command.productId(), existing, all, retainedIds);
        return productAdminDetailProcessor.getProduct(command.productId());
    }
}
