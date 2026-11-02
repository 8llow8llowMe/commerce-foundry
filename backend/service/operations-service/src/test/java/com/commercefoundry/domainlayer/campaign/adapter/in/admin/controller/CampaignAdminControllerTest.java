package com.commercefoundry.domainlayer.campaign.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.campaign.adapter.in.admin.dto.response.CampaignRecipientPreviewResponse;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignErrorCode;
import com.commercefoundry.domainlayer.campaign.application.exception.CampaignException;
import com.commercefoundry.domainlayer.campaign.application.port.in.CampaignAdminUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(CampaignAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class CampaignAdminControllerTest {
    private static final String PATH = "/api/v1/admin/campaigns/recipient-previews";
    @Autowired
    private MockMvcTester mvc;
    @MockitoBean
    private CampaignAdminUseCase useCase;

    private String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    @Test
    void signedAdminActorAndResponse() {
        when(useCase.preview(TestTokens.MEMBER_ID, List.of(2L, 1L, 2L))).thenReturn(
            new CampaignRecipientPreviewResponse(2, 1, 1, List.of(
                new CampaignRecipientPreviewResponse.Member("2", true), new CampaignRecipientPreviewResponse.Member("1", false))));
        var result = mvc.post().uri(PATH).header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[2,1,2]}").exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.members[0].memberId").isEqualTo("2");
        assertThat(result).bodyJson().extractingPath("$.dataBody.excludedCount").isEqualTo(1);
        verify(useCase).preview(TestTokens.MEMBER_ID, List.of(2L, 1L, 2L));
    }

    @Test
    void anonymousAndCustomerCannotQuery() {
        assertThat(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[1]}").exchange()).hasStatus(401);
        assertThat(mvc.post().uri(PATH).header("Authorization", bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[1]}").exchange()).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "{}|CAMPAIGN_101", "{\"memberIds\":[]}|CAMPAIGN_101", "{\"memberIds\":[null]}|CAMPAIGN_103",
        "{\"memberIds\":[0]}|CAMPAIGN_103", "{\"memberIds\":[-1]}|CAMPAIGN_103", "{broken|CAMPAIGN_100"
    }, delimiter = '|')
    void invalidInput(String body, String code) {
        var result = mvc.post().uri(PATH).header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(useCase);
    }

    @Test
    void rawListSizeBoundAndOneHundredAccepted() {
        String ids = String.join(",", IntStream.range(0, 100).mapToObj(i -> "1").toList());
        when(useCase.preview(anyLong(), anyList())).thenReturn(new CampaignRecipientPreviewResponse(1, 0, 1, List.of()));
        assertThat(mvc.post().uri(PATH).header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[" + ids + "]}").exchange()).hasStatusOk();
        clearInvocations(useCase);
        var result = mvc.post().uri(PATH).header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[" + ids + ",1]}").exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("CAMPAIGN_102");
        verifyNoInteractions(useCase);
    }

    @Test
    void unavailableEnvelope() {
        when(useCase.preview(anyLong(), anyList())).thenThrow(new CampaignException(CampaignErrorCode.CONSENT_SERVICE_UNAVAILABLE));
        var result = mvc.post().uri(PATH).header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[1]}").exchange();
        assertThat(result).hasStatus(503);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("CAMPAIGN_001");
    }
}
