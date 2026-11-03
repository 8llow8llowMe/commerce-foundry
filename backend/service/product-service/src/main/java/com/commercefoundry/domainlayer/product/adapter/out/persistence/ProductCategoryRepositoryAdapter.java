package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductCategoryRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import java.util.ArrayList;
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

    @Override
    public void deleteLinks(long productId, List<Long> categoryIds) {
        if (!categoryIds.isEmpty()) {
            productCategoryRepository.deleteLinks(productId, categoryIds);
        }
    }

    /** 엔티티가 {@code Persistable} 이라 새 행은 persist(INSERT) 로 간다 — 아이디가 겹치면 덮어쓰지 않고 PK 위반이다. */
    @Override
    public void insertLinks(long productId, List<Long> categoryIds, List<Long> ids) {
        if (categoryIds.size() != ids.size()) {
            throw new IllegalArgumentException("진열 카테고리와 아이디 수가 다릅니다: " + categoryIds.size() + " / " + ids.size());
        }
        List<ProductCategoryEntity> links = new ArrayList<>(categoryIds.size());
        for (int index = 0; index < categoryIds.size(); index++) {
            // display_order 는 "카테고리 안 상품 진열 순서"(어드민 수동 정렬용)다 — 상품 쪽 배열 순번이 아니라 기본값 0 으로 넣는다
            links.add(ProductCategoryEntity.builder().id(ids.get(index)).productId(productId).categoryId(categoryIds.get(index)).displayOrder(0).build());
        }
        productCategoryRepository.saveAll(links);
    }
}
