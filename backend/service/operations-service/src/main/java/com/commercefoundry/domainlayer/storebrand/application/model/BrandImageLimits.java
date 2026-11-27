package com.commercefoundry.domainlayer.storebrand.application.model;

public final class BrandImageLimits {
    public static final int UPLOAD_LIMIT = 1024 * 1024;
    public static final int STORED_LIMIT = 3 * UPLOAD_LIMIT;
    public static final String PNG_MIME = "image/png";

    private BrandImageLimits() {}
}
