package com.commercefoundry.domainlayer.storebrand.adapter.in.web.dto.response;

public final class BrandAssetWebResponses {
    public static final String PATH = "/api/v1/store/brand-assets";

    private BrandAssetWebResponses() {}

    public record Publication(String version, Asset logo, Asset darkLogo, Asset icon) {}
    public record Asset(String url, String altText) {}
}
