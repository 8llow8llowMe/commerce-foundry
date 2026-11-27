package com.commercefoundry.domainlayer.storebrand.application.info;

import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;

public record BrandAssetInfo(long version, BrandAssets draft, BrandRevision publication) {}
