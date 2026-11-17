package com.commercefoundry.domainlayer.picking.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.application.port.in.PickingBatchAdminUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(PickingBatchAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class PickingBatchAdminControllerTest {
    private static final String PATH = "/api/v1/admin/deliveries/picking-batches";
    @Autowired private MockMvcTester mvc;
    @MockitoBean private PickingBatchAdminUseCase useCase;

    private String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private PickingBatchAdminResponse response() {
        return new PickingBatchAdminResponse("4611686018427387904", 0L, false, 2, List.of(), List.of(), List.of());
    }

    @Test
    void createsReadsAndChecksWithResponseEnvelope() {
        given(useCase.create(List.of(1L))).willReturn(response());
        given(useCase.detail(1)).willReturn(response());
        given(useCase.check(1, 1, Map.of(10L, 2L))).willReturn(response());
        var created = mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"orderIds\":[\"1\"]}").exchange();
        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.dataBody.batchId").isEqualTo("4611686018427387904");
        assertThat(mvc.get().uri(PATH + "/1").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).exchange()).hasStatusOk();
        assertThat(mvc.post().uri(PATH + "/1/orders/1/checks").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"quantities\":{\"10\":2}}").exchange()).hasStatusOk();
    }

    @Test
    void rejectsAnonymousCustomersAndBadBodiesBeforeUseCase() {
        assertThat(mvc.get().uri(PATH + "/1").exchange()).hasStatus(401);
        assertThat(mvc.get().uri(PATH + "/1").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange()).hasStatus(403);
        assertThat(mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"orderIds\":[]}").exchange()).hasStatus(400);
        assertThat(mvc.post().uri(PATH + "/1/orders/1/checks").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content("{\"quantities\":{\"10\":0}}").exchange()).hasStatus(400);
        verifyNoInteractions(useCase);
    }
}
