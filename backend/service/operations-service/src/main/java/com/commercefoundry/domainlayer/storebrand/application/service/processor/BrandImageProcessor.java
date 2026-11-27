package com.commercefoundry.domainlayer.storebrand.application.service.processor;

import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.port.out.BrandImagePort;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Asset;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Kind;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BrandImageProcessor {
    private final BrandImagePort images;
    private final BrandPngValidator validator;

    public Kind kind(String name) {
        try {
            return Kind.valueOf(name);
        } catch (IllegalArgumentException exception) {
            throw new BrandAssetException(Code.INVALID_REQUEST);
        }
    }

    public UUID upload(Kind kind, String contentType, byte[] input) {
        byte[] png = validator.normalize(kind, contentType, input);
        UUID id = UUID.randomUUID();
        images.put(kind, id, png);
        return id;
    }

    public BrandAssets verify(BrandAssets assets) {
        return new BrandAssets(
            verify(Kind.LOGO, assets.logo()),
            verify(Kind.DARK_LOGO, assets.darkLogo()),
            verify(Kind.ICON, assets.icon())
        );
    }

    public byte[] read(Kind kind, UUID id) {
        return images.read(kind, id);
    }

    private Asset verify(Kind kind, Asset asset) {
        if (asset == null) {
            return null;
        }
        String alt = asset.altText();
        if (alt == null || alt.codePoints().anyMatch(Character::isISOControl)) {
            throw new BrandAssetException(Code.ALT_TEXT);
        }
        String text = alt.strip();
        if (text.length() > 160 || (kind != Kind.ICON && text.isBlank())) {
            throw new BrandAssetException(Code.ALT_TEXT);
        }
        if (asset.assetId() == null || !images.exists(kind, asset.assetId())) {
            throw new BrandAssetException(Code.ASSET);
        }
        return new Asset(asset.assetId(), text);
    }
}
