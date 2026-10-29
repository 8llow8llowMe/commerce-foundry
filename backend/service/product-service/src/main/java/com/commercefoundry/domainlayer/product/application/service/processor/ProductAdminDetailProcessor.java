package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.List;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 어드민 편집용 상세. 모든 자식을 상품 단위로 한 번씩 읽어 N+1 없이 원본 설정을 보존한다. */
@Component
@RequiredArgsConstructor
public class ProductAdminDetailProcessor {

    private final ProductRepositoryPort productRepositoryPort;
    private final ProductCategoryRepositoryPort productCategoryRepositoryPort;
    private final ProductOptionRepositoryPort productOptionRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final ProductImageRepositoryPort productImageRepositoryPort;
    private final ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;

    public ProductAdminDetailInfo getProduct(long productId) {
        Product product = productRepositoryPort.findAllByIdsIncludingHidden(List.of(productId)).stream()
            .filter(candidate -> !candidate.isDeleted())
            .findFirst().orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        // 과거 이관 데이터가 대표 연결을 빠뜨려도 저장된 대표 카테고리는 응답에 남긴다.
        TreeSet<Long> categoryIds = new TreeSet<>(productCategoryRepositoryPort.findCategoryIdsByProductId(productId));
        categoryIds.add(product.categoryId());
        return new ProductAdminDetailInfo(
            product, List.copyOf(categoryIds), productOptionRepositoryPort.findGroupsByProductId(productId),
            productOptionRepositoryPort.findValuesByProductId(productId), skuRepositoryPort.findAllByProductId(productId),
            productImageRepositoryPort.findAllByProductId(productId), productCustomOrderFieldRepositoryPort.findAllByProductId(productId)
        );
    }
}
