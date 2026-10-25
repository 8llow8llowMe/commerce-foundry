package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductImageEntity;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImageEntity, Long> {

    List<ProductImageEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);

    List<ProductImageEntity> findAllByProductIdInAndImageType(Collection<Long> productIds, ImageType imageType);

    List<ProductImageEntity> findAllByProductIdInAndImageTypeInOrderByProductIdAscDisplayOrderAscIdAsc(
        Collection<Long> productIds, Collection<ImageType> imageTypes
    );
}
