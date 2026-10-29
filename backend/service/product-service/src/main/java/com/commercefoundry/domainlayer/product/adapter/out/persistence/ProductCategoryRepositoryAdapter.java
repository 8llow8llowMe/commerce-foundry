package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductCategoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductCategoryRepositoryAdapter implements ProductCategoryRepositoryPort {

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;

    @Override
    public List<Long> findCategoryIdsByProductId(long productId) {
        return productCategoryRepository.findAllByProductIdOrderByCategoryIdAsc(productId).stream()
            .map(entity -> entity.getCategoryId()).toList();
    }

    /** 진열 카테고리를 먼저 보고 없을 때만 대표 카테고리를 본다 — 질의 순서 · 인덱스는 {@code ProductCustomRepositoryImpl#existsLiveProductInCategory}. */
    @Override
    public boolean existsProductInCategory(long categoryId) {
        return productRepository.existsLiveProductInCategory(categoryId);
    }
}
