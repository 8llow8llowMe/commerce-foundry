package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Product deletion changes only the header. Existing reservations and child records remain intact. */
@Component
@RequiredArgsConstructor
public class ProductDeleteProcessor {

    private final ProductRepositoryPort productRepositoryPort;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(long productId) {
        Product product = productRepositoryPort.findAllByIdsIncludingHidden(List.of(productId)).stream().findFirst()
            .orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        if (!product.isDeleted()) {
            productRepositoryPort.update(product.softDelete(clock.instant()));
        }
    }
}
