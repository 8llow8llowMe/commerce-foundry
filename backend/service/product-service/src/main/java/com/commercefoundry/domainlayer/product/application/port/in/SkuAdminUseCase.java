package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;

public interface SkuAdminUseCase {
    ProductDetailAdminResponse replace(SkuBulkUpdateCommand command);
}
