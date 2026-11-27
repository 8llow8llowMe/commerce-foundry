package com.commercefoundry.domainlayer.storebrand.application.port.in;

import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.request.BrandAssetAdminRequests;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.UUID;

public interface BrandAssetAdminUseCase {
    BrandAssetAdminResponses.Detail detail();
    BrandAssetAdminResponses.Detail save(long actor, BrandAssetAdminRequests.Draft draft);
    BrandAssetAdminResponses.Detail publish(long actor, BrandAssetAdminRequests.Publication publication);
    PageResponse<BrandAssetAdminResponses.Publication> history(int page, int size);
    BrandAssetAdminResponses.Upload upload(long actor, String kind, String contentType, byte[] input);
    byte[] preview(String kind, UUID id);
}
