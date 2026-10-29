package com.commercefoundry.domainlayer.product.application.info;

import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import java.util.List;

/** 편집용 저장 스냅숏. 고객 노출 필터나 기능 플래그를 적용하지 않는다. */
public record ProductAdminDetailInfo(
    Product product, List<Long> categoryIds, List<OptionGroup> optionGroups, List<OptionValue> optionValues,
    List<Sku> skus, List<ProductImage> images, List<CustomOrderField> customOrderFields
) {
    public ProductAdminDetailInfo {
        categoryIds = List.copyOf(categoryIds);
        optionGroups = List.copyOf(optionGroups);
        optionValues = List.copyOf(optionValues);
        skus = List.copyOf(skus);
        images = List.copyOf(images);
        customOrderFields = List.copyOf(customOrderFields);
    }
}
