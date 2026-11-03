package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.util.List;

public interface SkuAdminRepositoryPort {
    boolean claimProductVersion(long productId, long version);

    void replaceEditableProperties(List<Sku> skus);
}
