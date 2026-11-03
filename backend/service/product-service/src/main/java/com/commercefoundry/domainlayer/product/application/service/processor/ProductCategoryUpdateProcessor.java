package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.application.command.ProductCategoryUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 카테고리 잠금 안에서 상품 버전과 연결 차집합을 원자적으로 바꾼다. */
@Component
@RequiredArgsConstructor
public class ProductCategoryUpdateProcessor {
    private final ProductRepositoryPort productRepositoryPort;
    private final ProductCategoryRepositoryPort productCategoryRepositoryPort;
    private final ProductWriteProcessor productWriteProcessor;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductAdminDetailInfo update(ProductCategoryUpdateCommand command, CategoryTree tree) {
        var current = productRepositoryPort.findAllByIdsIncludingHidden(List.of(command.productId())).stream().findFirst()
            .filter(product -> !product.isDeleted()).orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        if (current.version() == null || current.version() != command.version()) {
            throw new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        }
        var requested = new LinkedHashSet<Long>();
        requested.add(command.categoryId());
        requested.addAll(command.categoryIds());
        productWriteProcessor.requireCategories(tree, List.copyOf(requested));
        // 대표가 같거나 요청이 완전히 같아도 버전을 올려 동시 기본 정보 수정 · 삭제를 막는다.
        productRepositoryPort.updateCategory(command.productId(), command.version(), command.categoryId());
        var existing = productCategoryRepositoryPort.findCategoryIdsByProductId(command.productId());
        var removed = existing.stream().filter(id -> !requested.contains(id)).toList();
        var added = requested.stream().filter(id -> !existing.contains(id)).toList();
        productCategoryRepositoryPort.deleteLinks(command.productId(), removed);
        productCategoryRepositoryPort.insertLinks(command.productId(), added, added.stream().map(id -> snowflakeIdGenerator.generateId()).toList());
        return productAdminDetailProcessor.getProduct(command.productId());
    }
}
