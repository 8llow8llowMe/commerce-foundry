package com.commercefoundry.domainlayer.storebrand.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.OperationsAdminAuditProcessor;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.request.BrandAssetAdminRequests;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.dto.response.BrandAssetAdminResponses;
import com.commercefoundry.domainlayer.storebrand.adapter.in.admin.presenter.BrandAssetAdminPresenter;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.port.in.BrandAssetAdminUseCase;
import com.commercefoundry.domainlayer.storebrand.application.service.processor.BrandImageProcessor;
import com.commercefoundry.domainlayer.storebrand.application.service.processor.BrandRevisionProcessor;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BrandAssetAdminFacade implements BrandAssetAdminUseCase {
    private final BrandRevisionProcessor revisions;
    private final BrandImageProcessor images;
    private final BrandAssetAdminPresenter presenter;
    private final OperationsAdminAuditProcessor audit;

    @Override
    public BrandAssetAdminResponses.Detail detail() {
        return presenter.detail(revisions.detail());
    }

    @Override
    public BrandAssetAdminResponses.Detail save(long actor, BrandAssetAdminRequests.Draft draft) {
        return audited(succeeded -> audit.brandDraftSaved(actor, succeeded), () -> {
            var current = revisions.current(BrandAssetAdminRequests.version(draft.expectedVersion(), true));
            return presenter.detail(revisions.save(actor, current, images.verify(draft.toAssets())));
        });
    }

    @Override
    public BrandAssetAdminResponses.Detail publish(long actor, BrandAssetAdminRequests.Publication publication) {
        return audited(succeeded -> audit.brandPublished(actor, succeeded), () -> {
            long expectedVersion = BrandAssetAdminRequests.version(publication.expectedVersion(), true);
            Long restoreVersion = publication.restoreVersion() == null ? null : BrandAssetAdminRequests.version(publication.restoreVersion(), false);
            var current = revisions.current(expectedVersion);
            if (current.version() == 0) {
                throw new BrandAssetException(Code.NOT_FOUND);
            }
            BrandAssets target = restoreVersion == null ? current.draft() : revisions.publication(restoreVersion).assets();
            return presenter.detail(revisions.publish(actor, expectedVersion, restoreVersion, images.verify(target)));
        });
    }

    @Override
    public PageResponse<BrandAssetAdminResponses.Publication> history(int page, int size) {
        return presenter.history(revisions.history(page, size));
    }

    @Override
    public BrandAssetAdminResponses.Upload upload(long actor, String kind, String contentType, byte[] input) {
        return audited(succeeded -> audit.brandUploaded(actor, succeeded), () -> {
            var target = images.kind(kind);
            return presenter.upload(target, images.upload(target, contentType, input));
        });
    }

    @Override
    public byte[] preview(String kind, UUID id) {
        return images.read(images.kind(kind), id);
    }

    private <T> T audited(Consumer<Boolean> auditResult, Supplier<T> operation) {
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException exception) {
            auditResult.accept(false);
            throw exception;
        }
        auditResult.accept(true);
        return result;
    }
}
