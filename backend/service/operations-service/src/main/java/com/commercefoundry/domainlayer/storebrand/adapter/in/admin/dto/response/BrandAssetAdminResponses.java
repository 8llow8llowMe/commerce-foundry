package com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response;

import java.time.Instant;
import java.util.UUID;

public final class BrandAssetAdminResponses {
    public static final String PATH = "/api/v1/admin/store/brand-assets";

    private BrandAssetAdminResponses() {}

    public record Detail(String version, Assets draft, Publication publication) {}
    public record Publication(String version, String actorMemberId, Instant createdAt, String restoredFromVersion, Assets assets) {}
    public record Assets(Asset logo, Asset darkLogo, Asset icon) {}
    public record Asset(UUID assetId, String altText, String previewUrl) {}
    public record Upload(UUID assetId, String previewUrl) {}
}
