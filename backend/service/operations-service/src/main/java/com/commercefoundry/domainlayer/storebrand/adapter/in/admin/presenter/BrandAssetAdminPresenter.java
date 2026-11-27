package com.commercefoundry.domainlayer.storebrand.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses.Asset;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses.Assets;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses.Detail;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses.Publication;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses.Upload;
import com.commercefoundry.domainlayer.storebrand.application.info.BrandAssetInfo;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Kind;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class BrandAssetAdminPresenter {
    public Detail detail(BrandAssetInfo info) {
        return new Detail(Long.toString(info.version()), assets(info.draft()),
            info.publication() == null ? null : revision(info.publication()));
    }

    public PageResponse<Publication> history(PageResponse<BrandRevision> history) {
        return new PageResponse<>(history.contents().stream().map(this::revision).toList(),
            history.page(), history.size(), history.totalElements(), history.totalPages());
    }

    public Upload upload(Kind kind, UUID id) {
        return new Upload(id, previewUrl(kind, id));
    }

    private Publication revision(BrandRevision revision) {
        return new Publication(Long.toString(revision.version()), Long.toString(revision.actorMemberId()),
            revision.createdAt(), revision.restoredFromVersion() == null ? null : revision.restoredFromVersion().toString(),
            assets(revision.assets()));
    }

    private Assets assets(BrandAssets assets) {
        return new Assets(asset(Kind.LOGO, assets.logo()), asset(Kind.DARK_LOGO, assets.darkLogo()), asset(Kind.ICON, assets.icon()));
    }

    private Asset asset(Kind kind, BrandAssets.Asset asset) {
        return asset == null ? null : new Asset(asset.assetId(), asset.altText(), previewUrl(kind, asset.assetId()));
    }

    private static String previewUrl(Kind kind, UUID id) {
        return BrandAssetAdminResponses.PATH + "/uploads/" + kind + "/" + id;
    }
}
