package com.commercefoundry.domainlayer.mediaupload.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.ProductAdminAuditProcessor;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.dto.response.MediaVideoUploadResponse;
import com.commercefoundry.domainlayer.mediaupload.adapter.in.admin.presenter.MediaVideoUploadAdminPresenter;
import com.commercefoundry.domainlayer.mediaupload.application.port.in.MediaVideoUploadAdminUseCase;
import com.commercefoundry.domainlayer.mediaupload.application.service.processor.MediaUploadProcessor;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaKind;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 메인 전시 영상 업로드 (#796). 흐름 · 감사는 이미지 업로드와 같고 종류만 {@link MediaKind#VIDEO} 다. */
@Service
@RequiredArgsConstructor
// Storage I/O must not hold or join a caller's database transaction.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MediaVideoUploadAdminFacade implements MediaVideoUploadAdminUseCase {
    private final MediaUploadProcessor processor;
    private final MediaVideoUploadAdminPresenter presenter;
    private final ProductAdminAuditProcessor audit;

    @Override
    public MediaVideoUploadResponse.Ticket issue(long actorId, String contentType, long contentLength) {
        UUID id = null;
        boolean succeeded = false;
        try {
            var info = processor.issue(actorId, MediaKind.VIDEO, contentType, contentLength);
            id = processor.parseKey(MediaKind.VIDEO, info.uploadKey()).id();
            var response = presenter.toResponse(info);
            succeeded = true;
            return response;
        } finally {
            audit.mediaVideoUploadIssued(actorId, id, succeeded);
        }
    }

    @Override
    public MediaVideoUploadResponse.Confirmed confirm(long actorId, String uploadKey) {
        UUID id = null;
        boolean succeeded = false;
        try {
            var key = processor.parseKey(MediaKind.VIDEO, uploadKey);
            id = key.id();
            var response = presenter.toResponse(processor.confirm(actorId, key));
            succeeded = true;
            return response;
        } finally {
            audit.mediaVideoUploadConfirmed(actorId, id, succeeded);
        }
    }
}
