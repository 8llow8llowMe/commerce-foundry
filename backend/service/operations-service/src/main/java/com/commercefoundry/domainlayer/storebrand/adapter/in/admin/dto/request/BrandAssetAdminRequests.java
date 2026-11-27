package com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import java.util.UUID;

public final class BrandAssetAdminRequests {
    private BrandAssetAdminRequests() {}

    public record Draft(String expectedVersion, Assets assets) {
        public BrandAssets toAssets() {
            if (assets == null) {
                throw new BrandAssetException(Code.INVALID_REQUEST);
            }
            return assets.toDomain();
        }
    }

    public record Publication(String expectedVersion, String restoreVersion) {}

    public record Assets(Asset logo, Asset darkLogo, Asset icon) {
        BrandAssets toDomain() {
            return new BrandAssets(toAsset(logo), toAsset(darkLogo), toAsset(icon));
        }

        private static BrandAssets.Asset toAsset(Asset asset) {
            return asset == null ? null : new BrandAssets.Asset(asset.assetId(), asset.altText());
        }
    }

    public record Asset(UUID assetId, String altText) {}

    public static long version(String value, boolean allowZero) {
        if (value != null && value.matches("0|[1-9][0-9]{0,18}")) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0 || allowZero) {
                    return parsed;
                }
            } catch (NumberFormatException exception) {
                throw new BrandAssetException(Code.VERSION);
            }
        }
        throw new BrandAssetException(Code.VERSION);
    }
}
