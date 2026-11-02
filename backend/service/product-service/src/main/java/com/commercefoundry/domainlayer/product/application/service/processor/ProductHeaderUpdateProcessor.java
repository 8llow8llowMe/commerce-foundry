package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductHeaderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Versioned header replacement; child records, prices and reservations are untouched. */
@Component
@RequiredArgsConstructor
public class ProductHeaderUpdateProcessor {

    private final ProductRepositoryPort productRepositoryPort;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductAdminDetailInfo update(ProductHeaderUpdateCommand command, String sanitizedHtml) {
        Product current = productRepositoryPort.findAllByIdsIncludingHidden(List.of(command.productId())).stream().findFirst()
            .filter(product -> !product.isDeleted())
            .orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        if (current.version() == null || current.version() != command.version()) {
            throw new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        }
        productRepositoryPort.update(new Product(current.id(), current.categoryId(), command.name(), command.summary(), sanitizedHtml,
            command.saleStatus(), command.visible(), current.salePrice(), current.listPrice(), current.maxQuantityPerOrder(),
            current.customOrder(), current.customOrderNotice(), current.customOrderLeadTimeDays(), current.preorder(),
            current.expectedShipDate(), current.version(), current.deletedAt(), current.createdByMemberId()));
        return productAdminDetailProcessor.getProduct(command.productId());
    }
}
