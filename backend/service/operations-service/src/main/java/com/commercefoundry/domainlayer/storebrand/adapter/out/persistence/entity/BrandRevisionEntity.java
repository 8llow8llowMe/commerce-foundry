package com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Asset;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_brand_revision", comment = "스토어 브랜드 편집·게시·복구 이력",
    check = {
        @CheckConstraint(name = "ck_store_brand_revision_pointers", constraint = """
            version > 0 and draft_version between 1 and version and
            (published_version is null or published_version between 1 and version) and
            (draft_version = version or (published_version is not null and published_version = version)) and
            (restored_from_version is null or
                (published_version is not null and published_version = version and restored_from_version between 1 and version - 1))
            """),
        @CheckConstraint(name = "ck_store_brand_revision_asset_pairs", constraint = """
            (logo_id is null) = (logo_alt_text is null) and
            (dark_logo_id is null) = (dark_logo_alt_text is null) and
            (icon_id is null) = (icon_alt_text is null)
            """)
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrandRevisionEntity {
    @Id
    private Long version;

    @Column(nullable = false)
    private long draftVersion;

    private Long publishedVersion;
    private Long restoredFromVersion;

    @Column(nullable = false, updatable = false, comment = "작업 관리자 아이디 (외부 FK: auth-service member.id)")
    private Long actorMemberId;

    @Column(nullable = false)
    private Instant createdAt;

    private UUID logoId;
    private UUID darkLogoId;
    private UUID iconId;

    @Column(length = 160)
    private String logoAltText;

    @Column(length = 160)
    private String darkLogoAltText;

    @Column(length = 160)
    private String iconAltText;

    public static BrandRevisionEntity from(BrandRevision revision) {
        var entity = new BrandRevisionEntity();
        entity.version = revision.version();
        entity.draftVersion = revision.draftVersion();
        entity.publishedVersion = revision.publishedVersion();
        entity.restoredFromVersion = revision.restoredFromVersion();
        entity.actorMemberId = revision.actorMemberId();
        entity.createdAt = revision.createdAt();
        var assets = revision.assets();
        if (assets.logo() != null) {
            entity.logoId = assets.logo().assetId();
            entity.logoAltText = assets.logo().altText();
        }
        if (assets.darkLogo() != null) {
            entity.darkLogoId = assets.darkLogo().assetId();
            entity.darkLogoAltText = assets.darkLogo().altText();
        }
        if (assets.icon() != null) {
            entity.iconId = assets.icon().assetId();
            entity.iconAltText = assets.icon().altText();
        }
        return entity;
    }

    public BrandRevision toDomain() {
        var assets = new BrandAssets(asset(logoId, logoAltText), asset(darkLogoId, darkLogoAltText), asset(iconId, iconAltText));
        return new BrandRevision(version, draftVersion, publishedVersion, restoredFromVersion, actorMemberId, createdAt, assets);
    }

    private static Asset asset(UUID id, String text) {
        return id == null ? null : new Asset(id, text);
    }
}
