package com.commercefoundry.domainlayer.campaign.application.service.facade;

import com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.response.CampaignRecipientPreviewResponse;
import com.commercefoundry.domainlayer.campaign.adapter.in.admin.presenter.CampaignAdminPresenter;
import com.commercefoundry.domainlayer.campaign.application.port.in.CampaignAdminUseCase;
import com.commercefoundry.domainlayer.campaign.application.service.processor.CampaignAdminAuditProcessor;
import com.commercefoundry.domainlayer.campaign.application.service.processor.CampaignRecipientPreviewProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CampaignAdminFacade implements CampaignAdminUseCase {
    private final CampaignRecipientPreviewProcessor previewProcessor;
    private final CampaignAdminPresenter presenter;
    private final CampaignAdminAuditProcessor auditProcessor;

    // 원격 auth I/O를 DB 트랜잭션으로 감싸지 않는다.
    @Override
    public CampaignRecipientPreviewResponse preview(Long actorMemberId, List<Long> memberIds) {
        CampaignRecipientPreviewResponse response;
        try {
            response = presenter.toResponse(previewProcessor.preview(memberIds));
        } catch (RuntimeException exception) {
            auditProcessor.previewFinished(actorMemberId, false);
            throw exception;
        }
        auditProcessor.previewFinished(actorMemberId, true);
        return response;
    }
}
