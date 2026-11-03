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
    public void replaceStillImages(long productId, List<ProductImage> existing, List<ProductImage> requested, List<Long> retainedIds) {
        var removed = existing.stream().filter(i -> i.imageType() != ImageType.VIDEO && !retainedIds.contains(i.id())).map(ProductImage::id).toList();
        if (!removed.isEmpty()) productImageRepository.deleteImages(productId, removed);
        var occupied = new java.util.HashSet<Integer>();
        existing.stream().filter(i -> retainedIds.contains(i.id()) && i.imageType() == ImageType.GALLERY)
            .map(ProductImage::displayOrder).forEach(occupied::add);
        var now = java.time.Instant.now();
        int temporary = -1;
        // At most 8 legacy slots and 8 reserved slots: no arithmetic on arbitrary legacy values.
        for (var image : requested) {
            if (!retainedIds.contains(image.id())) continue;
            while (occupied.contains(temporary)) temporary--;
            occupied.add(temporary);
            productImageRepository.updatePlacement(productId, image.id(), ImageType.GALLERY, temporary--, image.altText(), now);
        }
        for (var image : requested) {
            if (retainedIds.contains(image.id())) {
                productImageRepository.updatePlacement(productId, image.id(), image.imageType(), image.displayOrder(), image.altText(), now);
            }
        }
        insertAll(requested.stream().filter(i -> !retainedIds.contains(i.id())).toList());
        productImageRepository.flush();
    }

    @Override
    public void insertAll(List<ProductImage> images) {
        productImageRepository.saveAll(images.stream().map(productImageMapper::toEntity).toList());
    }

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

    @Override
    public List<ProductImage> findStillImagesByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productImageMapper.toDomains(productImageRepository.findAllByProductIdInAndImageTypeInOrderByProductIdAscDisplayOrderAscIdAsc(
            productIds, List.of(ImageType.THUMBNAIL, ImageType.GALLERY)
        ));
    }
}
