package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductCategoryRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductCategoryRepositoryAdapter implements ProductCategoryRepositoryPort {

    private final ProductCategoryRepository productCategoryRepository;
    private final ProductRepository productRepository;

    /**
     * 진열 카테고리(인덱스)를 먼저 보고, 없을 때만 대표 카테고리를 본다 — 불변식이 지켜진 데이터면 첫 질의에서 끝난다.
     * {@code product.category_id} 에는 인덱스가 없어 두 번째 질의는 표를 훑는다. 어드민 카테고리 삭제에서만 부르므로 인덱스를 더하지 않는다.
     */
    @Override
    public boolean existsProductInCategory(long categoryId) {
        return productCategoryRepository.existsByCategoryId(categoryId) || productRepository.existsByCategoryId(categoryId);
    }
}
