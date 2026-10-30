package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.info.ProductRegistrationFactInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRegistrationFactRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductRegistrationFactProcessor {
    private final ProductRegistrationFactRepositoryPort repositoryPort;

    public ProductRegistrationFactInfo getFact(long memberId) {
        return new ProductRegistrationFactInfo(repositoryPort.existsPurchasableProduct(),
            repositoryPort.findFirstRegisteredAt(memberId).orElse(null));
    }
}
