package com.commercefoundry.domainlayer.mediaupload.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaImageUploadResponse;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.presenter.MediaImageUploadAdminPresenter;
import com.commercefoundry.domainlayer.mediaupload.application.port.in.MediaImageUploadAdminUseCase;
import com.commercefoundry.domainlayer.mediaupload.application.service.processor.MediaImageUploadProcessor;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageUploadKey;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// Storage I/O must not hold or join a caller's database transaction.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MediaImageUploadAdminFacade implements MediaImageUploadAdminUseCase {
    private final MediaImageUploadProcessor processor;
    private final MediaImageUploadAdminPresenter presenter;
    private final ProductAdminAuditProcessor audit;

    @Override
    public MediaImageUploadResponse.Ticket issue(long actorId, String contentType, long contentLength) {
        UUID id = null;
        boolean succeeded = false;
        try {
            var info = processor.issue(actorId, contentType, contentLength);
            id = processor.parseKey(info.uploadKey()).id();
            var response = presenter.toResponse(info);
            succeeded = true;
            return response;
        } finally {
            audit.mediaImageUploadIssued(actorId, id, succeeded);
        }
    }

    @Override
    public MediaImageUploadResponse.Confirmed confirm(long actorId, String uploadKey) {
        UUID id = null;
        boolean succeeded = false;
        try {
            var key = processor.parseKey(uploadKey);
            id = key.id();
            var response = presenter.toResponse(processor.confirm(actorId, key));
            succeeded = true;
            return response;
        } finally {
            audit.mediaImageUploadConfirmed(actorId, id, succeeded);
        }
    }
}
