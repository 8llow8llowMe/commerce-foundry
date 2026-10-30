package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRegistrationFactRepositoryPort;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductRegistrationFactRepositoryAdapter implements ProductRegistrationFactRepositoryPort {
    private final ProductRepository repository;

    @Override
    public boolean existsPurchasableProduct() {
        return repository.existsPurchasableProduct();
    }

    @Override
    public Optional<Instant> findFirstRegisteredAt(long memberId) {
        return repository.findFirstRegisteredAt(memberId);
    }
}
