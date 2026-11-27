package com.commercefoundry.domainlayer.storebrand.domain.model;

import java.util.UUID;

public record BrandAssets(Asset logo, Asset darkLogo, Asset icon) {
    public static final BrandAssets EMPTY = new BrandAssets(null, null, null);

    public Asset asset(Kind kind) {
        return switch (kind) {
            case LOGO -> logo;
            case DARK_LOGO -> darkLogo;
            case ICON -> icon;
        };
    }

    public enum Kind { LOGO, DARK_LOGO, ICON }

    public record Asset(UUID assetId, String altText) {}
}
