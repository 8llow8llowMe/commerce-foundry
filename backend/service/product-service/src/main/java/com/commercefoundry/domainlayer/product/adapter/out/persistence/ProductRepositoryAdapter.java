package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapper;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepositoryPort {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Override
    public Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria) {
        return productRepository.searchVisible(criteria);
    }

    @Override
    public Optional<Product> findVisibleById(long productId) {
        return productRepository.findVisibleById(productId).map(productMapper::toDomain);
    }

    @Override
    public List<Product> findAllByIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productMapper.toDomains(productRepository.findAllById(productIds));
    }
}
