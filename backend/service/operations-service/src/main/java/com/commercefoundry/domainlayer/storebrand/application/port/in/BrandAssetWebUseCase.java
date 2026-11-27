package com.commercefoundry.domainlayer.storebrand.application.port.in;

import com.commercefoundry.domainlayer.storebrand.adapter.in.web.dto.response.BrandAssetWebResponses;
import java.util.UUID;

public interface BrandAssetWebUseCase {
    BrandAssetWebResponses.Publication publication();
    byte[] image(long version, String kind, UUID id);
}
