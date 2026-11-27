package com.commercefoundry.domainlayer.storebrand.application.service;

import com.commercefoundry.domainlayer.storebrand.adapter.in.web.dto.response.BrandAssetWebResponses;
import com.commercefoundry.domainlayer.storebrand.adapter.in.web.presenter.BrandAssetWebPresenter;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.port.in.BrandAssetWebUseCase;
import com.commercefoundry.domainlayer.storebrand.application.service.processor.BrandImageProcessor;
import com.commercefoundry.domainlayer.storebrand.application.service.processor.BrandRevisionProcessor;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BrandAssetWebFacade implements BrandAssetWebUseCase {
    private final BrandRevisionProcessor revisions;
    private final BrandImageProcessor images;
    private final BrandAssetWebPresenter presenter;

    @Override
    public BrandAssetWebResponses.Publication publication() {
        return presenter.publication(revisions.publication());
    }

    @Override
    public byte[] image(long version, String kind, UUID id) {
        var target = images.kind(kind);
        var published = revisions.publication().filter(revision -> revision.version() == version)
            .orElseThrow(() -> new BrandAssetException(Code.NOT_FOUND));
        var asset = published.assets().asset(target);
        if (asset == null || !id.equals(asset.assetId())) {
            throw new BrandAssetException(Code.NOT_FOUND);
        }
        return images.read(target, id);
    }
}
