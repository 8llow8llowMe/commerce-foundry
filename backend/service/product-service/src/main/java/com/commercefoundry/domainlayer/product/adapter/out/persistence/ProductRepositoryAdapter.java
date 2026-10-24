package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapper;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepositoryPort {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Override
    public Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria) {
        Set<Long> categoryIds = criteria.categoryIds();
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new SliceImpl<>(List.of(), PageRequest.of(criteria.page(), criteria.size()), false);
        }
        return productRepository.searchVisible(criteria);
    }

    @Override
    public List<ProductListQueryResult> findVisibleListRowsByIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findVisibleListRowsByIds(productIds);
    }

    @Override
    public Optional<Product> findVisibleById(long productId) {
        return productRepository.findVisibleById(productId).map(productMapper::toDomain);
    }

    @Override
    public List<Product> findAllByIdsIncludingHidden(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productMapper.toDomains(productRepository.findAllById(productIds));
    }

    @Override
    public Page<ProductAdminListQueryResult> searchForAdmin(ProductAdminSearchCriteria criteria) {
        Set<Long> categoryIds = criteria.categoryIds();
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new PageImpl<>(List.of(), PageRequest.of(criteria.page(), criteria.size()), 0);
        }
        return productRepository.searchForAdmin(criteria);
    }
}
