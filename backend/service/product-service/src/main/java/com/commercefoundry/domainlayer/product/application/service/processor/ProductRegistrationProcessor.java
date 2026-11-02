package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 상품 · 자식 · 이미지를 한 트랜잭션에 등록한다. 저장소 복사는 호출 전에 끝나 있어 잠금 중 외부 I/O 가 없다. */
@Component
@RequiredArgsConstructor
public class ProductRegistrationProcessor {

    private final ProductWriteProcessor productWriteProcessor;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;
    private final ProductImageRepositoryPort productImageRepositoryPort;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductAdminDetailInfo register(long productId, ProductCreateCommand command, String html, List<Long> categoryIds, List<ProductImage> images,
                                         CategoryTree lockedCategories) {
        productWriteProcessor.requireCategories(lockedCategories, categoryIds);
        productWriteProcessor.create(command, html, categoryIds, productId);
        productImageRepositoryPort.insertAll(images);
        return productAdminDetailProcessor.getProduct(productId);
    }
}
