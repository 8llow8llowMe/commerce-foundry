package com.commercefoundry.domainlayer.product.application.command;

import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import java.util.List;

/** actorMemberId is obtained only from the access token. */
public record ProductImagesUpdateCommand(long productId, long version, long actorMemberId, List<Image> images) {
    public record Image(Long imageId, String uploadKey, ImageType imageType, String altText) { }
}
