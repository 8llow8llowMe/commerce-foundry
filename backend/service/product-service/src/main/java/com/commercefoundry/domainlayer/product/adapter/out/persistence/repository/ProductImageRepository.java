package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductImageEntity;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImageEntity, Long> {

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from ProductImageEntity i where i.productId = :productId and i.id in :ids")
    int deleteImages(long productId, Collection<Long> ids);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update ProductImageEntity i set i.imageType = :imageType, i.displayOrder = :displayOrder, "
        + "i.altText = :altText, i.updatedAt = :updatedAt where i.productId = :productId and i.id = :id")
    int updatePlacement(long productId, long id, ImageType imageType, int displayOrder, String altText, java.time.Instant updatedAt);

    List<ProductImageEntity> findAllByProductIdOrderByDisplayOrderAscIdAsc(Long productId);

    List<ProductImageEntity> findAllByProductIdInAndImageType(Collection<Long> productIds, ImageType imageType);

    List<ProductImageEntity> findAllByProductIdInAndImageTypeInOrderByProductIdAscDisplayOrderAscIdAsc(
        Collection<Long> productIds, Collection<ImageType> imageTypes
    );
}
