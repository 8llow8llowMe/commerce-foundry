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
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"OWNER", "MANAGER", "STAFF"})
    @DisplayName("수신 대상 미리보기는 CS_READ 라 OWNER · MANAGER · STAFF 도 유스케이스에 닿는다 — 저장 · 발송이 없고 회원 목록과 같은 노출 수준이다 (#588)")
    void csReadersMayPreview(MemberRole role) {
        when(useCase.preview(anyLong(), anyList())).thenReturn(new CampaignRecipientPreviewResponse(1, 1, 0, List.of()));

        assertThat(mvc.post().uri(PATH).header("Authorization", bearer(role))
            .contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[1]}").exchange()).hasStatusOk();
        verify(useCase).preview(TestTokens.MEMBER_ID, List.of(1L));
    }

    @Test
    void anonymousAndCustomerCannotQuery() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[1]}").exchange().getResponse(),
            SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(PATH).header("Authorization", bearer(MemberRole.CUSTOMER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"memberIds\":[1]}").exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
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
