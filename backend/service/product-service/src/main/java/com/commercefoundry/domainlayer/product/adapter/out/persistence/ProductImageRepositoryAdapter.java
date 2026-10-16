package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductImageRepository;
import com.commercefoundry.domainlayer.product.application.mapper.ProductImageMapper;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductImageRepositoryAdapter implements ProductImageRepositoryPort {

    private final ProductImageRepository productImageRepository;
    private final ProductImageMapper productImageMapper;

    @Override
    public List<ProductImage> findAllByProductId(long productId) {
        return productImageMapper.toDomains(productImageRepository.findAllByProductIdOrderByDisplayOrderAscIdAsc(productId));
    }

    @Override
    public List<ProductImage> findThumbnailsByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productImageMapper.toDomains(productImageRepository.findAllByProductIdInAndImageType(productIds, ImageType.THUMBNAIL));
    }
}
