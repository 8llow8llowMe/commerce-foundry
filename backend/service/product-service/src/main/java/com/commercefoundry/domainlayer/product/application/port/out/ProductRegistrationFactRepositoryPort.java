package com.commercefoundry.domainlayer.product.application.port.out;

import java.time.Instant;
import java.util.Optional;

public interface ProductRegistrationFactRepositoryPort {
    boolean existsPurchasableProduct();
    Optional<Instant> findFirstRegisteredAt(long memberId);
}
