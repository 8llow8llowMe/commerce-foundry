package com.commercefoundry.domainlayer.storebrand.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.storebrand.adapter.in.web.dto.response.BrandAssetWebResponses;
import com.commercefoundry.domainlayer.storebrand.adapter.in.web.dto.response.BrandAssetWebResponses.Asset;
import com.commercefoundry.domainlayer.storebrand.adapter.in.web.dto.response.BrandAssetWebResponses.Publication;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Kind;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class BrandAssetWebPresenter {
    public Publication publication(Optional<BrandRevision> revision) {
        return revision.map(this::present).orElseGet(() -> new Publication("0", null, null, null));
    }

    private Publication present(BrandRevision revision) {
        BrandAssets assets = revision.assets();
        long version = revision.version();
        return new Publication(Long.toString(version), asset(version, Kind.LOGO, assets.logo()),
            asset(version, Kind.DARK_LOGO, assets.darkLogo()), asset(version, Kind.ICON, assets.icon()));
    }

    private Asset asset(long version, Kind kind, BrandAssets.Asset asset) {
        return asset == null ? null
            : new Asset(BrandAssetWebResponses.PATH + "/images/" + version + "/" + kind + "/" + asset.assetId(), asset.altText());
    }
}
