package com.commercefoundry.domainlayer.member.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item.MarketingConsentInternalItem;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MarketingConsentInternalResponse;
import com.commercefoundry.domainlayer.member.application.port.in.MarketingConsentInternalUseCase;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(MarketingConsentInternalController.class)
@Import({InternalResourceServerConfigurer.class, InternalTokenPropertiesConfig.class, JwtVerificationPropertiesConfig.class, ResourceServerSecurityConfigurer.class})
class MarketingConsentInternalControllerTest {

    private static final String PATH = "/internal/v1/marketing-consents/query";

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    MarketingConsentInternalUseCase useCase;

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void operationsReceivesOnlyIdsAndConsent() {
        given(useCase.query(List.of(2L, 1L))).willReturn(new MarketingConsentInternalResponse(List.of(
            new MarketingConsentInternalItem("2", true), new MarketingConsentInternalItem("1", false))));

        MvcTestResult result = request("{\"memberIds\":[2,1]}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.members[0]").isEqualTo(Map.of("memberId", "2", "marketingAgreed", true));
        assertThat(result).bodyJson().extractingPath("$.dataBody.members[1]").isEqualTo(Map.of("memberId", "1", "marketingAgreed", false));
        verify(useCase).query(List.of(2L, 1L));
    }

    @Test
    @WithMockUser(authorities = "SERVICE_order-service")
    void otherServiceIsForbidden() {
        assertThat(request("{\"memberIds\":[1]}")).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"memberIds\":null}", "{\"memberIds\":[]}"})
    @WithMockUser(authorities = "SERVICE_operations-service")
    void missingIdsAreRejected(String body) {
        rejected(body, "AUTH_154");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"memberIds\":[0]}", "{\"memberIds\":[-1]}", "{\"memberIds\":[null]}"})
    @WithMockUser(authorities = "SERVICE_operations-service")
    void invalidIdsAreRejected(String body) {
        rejected(body, "AUTH_156");
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void duplicateIdsStillCountTowardsRawLimit() {
        rejected("{\"memberIds\":[" + String.join(",", Collections.nCopies(101, "1")) + "]}", "AUTH_155");
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void oneHundredIdsAreAllowed() {
        given(useCase.query(Collections.nCopies(100, 1L))).willReturn(new MarketingConsentInternalResponse(List.of()));
        assertThat(request("{\"memberIds\":[" + String.join(",", Collections.nCopies(100, "1")) + "]}")).hasStatusOk();
        verify(useCase).query(Collections.nCopies(100, 1L));
    }

    @Test
    @WithMockUser(authorities = "SERVICE_operations-service")
    void malformedIdsAreRejected() {
        rejected("{\"memberIds\":[\"not-an-id\"]}", "AUTH_100");
    }

    private MvcTestResult request(String body) {
        return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private void rejected(String body, String code) {
        MvcTestResult result = request(body);
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(useCase);
    }
}
