package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.ProductDetailAdminResponse;
import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;

public interface SkuAdminUseCase {
    ProductDetailAdminResponse replace(SkuBulkUpdateCommand command);

    /** 옵션 값 · 새 조합 SKU 추가 (#9 PR-C1). */
    ProductDetailAdminResponse add(com.commercefoundry.domainlayer.product.application.command.SkuAddCommand command);

    /** 옵션 그룹 · 값 은퇴 (#9 PR-C2). */
    ProductDetailAdminResponse retireOptions(com.commercefoundry.domainlayer.product.application.command.OptionRetirementCommand command);
}
