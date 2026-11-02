package com.commercefoundry.domainlayer.campaign.application.service.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.campaign.adapter.in.admin.presenter.CampaignAdminPresenter;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignErrorCode;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignException;
import com.commercefoundry.domainlayer.campaign.application.port.out.CampaignConsentQueryPort;
import com.commercefoundry.domainlayer.campaign.application.port.out.query.CampaignConsentQueryResult;
import com.commercefoundry.domainlayer.campaign.application.service.processor.CampaignAdminAuditProcessor;
import com.commercefoundry.domainlayer.campaign.application.service.processor.CampaignRecipientPreviewProcessor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CampaignAdminFacadeTest {
    private final CampaignConsentQueryPort port = mock(CampaignConsentQueryPort.class);
    private final CampaignAdminFacade facade = new CampaignAdminFacade(
        new CampaignRecipientPreviewProcessor(port), new CampaignAdminPresenter(), new CampaignAdminAuditProcessor());

    @Test
    void firstOrderCountsAndAudit(CapturedOutput output) {
        when(port.findConsents(List.of(9002L, 9001L, 9003L))).thenReturn(new CampaignConsentQueryResult(Set.of(9001L)));
        var result = facade.preview(7L, List.of(9002L, 9001L, 9002L, 9003L));
        assertThat(result.recipientCount()).isEqualTo(3);
        assertThat(result.agreedCount()).isEqualTo(1);
        assertThat(result.excludedCount()).isEqualTo(2);
        assertThat(result.members()).extracting(member -> member.memberId()).containsExactly("9002", "9001", "9003");
        assertThat(result.members()).extracting(member -> member.marketingAgreed()).containsExactly(false, true, false);
        verify(port).findConsents(List.of(9002L, 9001L, 9003L));
        verifyNoMoreInteractions(port);
        assertThat(output.getOut()).containsOnlyOnce(
            "action=CAMPAIGN_RECIPIENT_PREVIEW actorMemberId=7 target=campaign-preview:recipients result=SUCCESS");
        assertThat(output.getAll()).doesNotContain("9001", "9002", "9003", "FAILURE");
    }

    @Test
    void failureAuditedWithoutRecipientData(CapturedOutput output) {
        when(port.findConsents(anyList())).thenThrow(new CampaignException(CampaignErrorCode.CONSENT_SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> facade.preview(7L, List.of(9001L))).isInstanceOf(CampaignException.class);
        assertThat(output.getOut()).containsOnlyOnce(
            "action=CAMPAIGN_RECIPIENT_PREVIEW actorMemberId=7 target=campaign-preview:recipients result=FAILURE");
        assertThat(output.getAll()).doesNotContain("9001", "SUCCESS");
    }
}
