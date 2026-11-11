package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.exception.SizeChartDraftAdminExceptionHandler;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.presenter.SizeChartDraftAdminPresenter;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.port.in.SizeChartDraftUseCase;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraftStatus;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartImageInspectionInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartImageSourceInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageSourceRejection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(SizeChartDraftAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class,
    SizeChartDraftAdminPresenter.class, SizeChartDraftAdminExceptionHandler.class})
class SizeChartDraftAdminControllerTest {
    private static final String CREATE = "/api/v1/admin/products/700/size-chart/drafts";
    private static final String DRAFTS = "/api/v1/admin/size-chart-drafts";
    private static final String IMAGE_SOURCES = "/api/v1/admin/products/700/size-chart/image-sources";
    private static final String KEY = "11111111-1111-4111-8111-111111111111";
    private static final String BODY = "{\"templateId\":\"1\",\"mode\":\"TEXT\",\"sourceText\":\"S 가슴 50\"}";
    private static final String APPLY = """
        {"expectedDraftVersion":2,"correctedChart":{"templateId":"1","rows":[
          {"sizeLabel":"S","displayOrder":0,"cells":[{"columnId":"10","value":50.25}]}]},"acknowledgedWarningIds":[]}
        """;

    @Autowired private MockMvcTester mvc;
    @MockitoBean private SizeChartDraftUseCase useCase;
    private final SizeChartDraftAdminPresenter presenter = new SizeChartDraftAdminPresenter();

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/admin/size-chart-drafts", "GET,/api/v1/admin/size-chart-drafts/9",
        "POST,/api/v1/admin/products/700/size-chart/drafts", "POST,/api/v1/admin/size-chart-drafts/9/refresh",
        "POST,/api/v1/admin/size-chart-drafts/9/apply", "GET,/api/v1/admin/products/700/size-chart/image-sources",
        "GET,/api/v1/admin/products/700/size-chart/image-sources/IMG-1/inspection"})
    void customerAndAnonymousCannotCallAnyDraftHandler(String method, String path) {
        var verb = HttpMethod.valueOf(method);
        SecurityEnvelopeAssertions.assertEnvelope(exchange(verb, path, BODY, KEY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(exchange(verb, path, BODY, KEY, null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(useCase);
    }

    @Test
    void imageSourcesExposeOnlyStringIdsPublicPreviewAndBusinessReason() {
        given(useCase.imageSourcesAdmin(700)).willReturn(presenter.imageSources(List.of(
            new SizeChartImageSourceInfo("IMG-4611686018427387905", "https://cdn.example.com/products/700/4611686018427387905.png", true, null),
            new SizeChartImageSourceInfo("DESC-" + "0".repeat(32), null, false, SizeChartImageSourceRejection.EXTERNAL_IMAGE))));
        var result = exchange(HttpMethod.GET, IMAGE_SOURCES, null, null, MemberRole.ADMIN);
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody[0].sourceId").isEqualTo("IMG-4611686018427387905");
        assertThat(result).bodyJson().extractingPath("$.dataBody[0].eligible").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataBody[1].reason.code").isEqualTo("EXTERNAL_IMAGE");
        assertThat(result).bodyJson().extractingPath("$.dataBody[1].reason.name").isEqualTo("외부 주소 이미지");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void inspectionReturnsHashTypeAndLengthOnly() throws Exception {
        given(useCase.inspectImageAdmin(700, "IMG-4611686018427387905"))
            .willReturn(presenter.inspection(new SizeChartImageInspectionInfo("IMG-4611686018427387905", "a".repeat(64), "image/png", 12)));
        var result = exchange(HttpMethod.GET, IMAGE_SOURCES + "/IMG-4611686018427387905/inspection", null, null, MemberRole.ADMIN);
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody").asMap().containsOnlyKeys("sourceId", "contentHash", "contentType", "contentLength")
            .containsEntry("contentHash", "a".repeat(64));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("products/");
    }

    @Test
    void readOnlyStaffCanListCandidatesButCannotInspectBytes() {
        given(useCase.imageSourcesAdmin(700)).willReturn(List.of());
        assertThat(exchange(HttpMethod.GET, IMAGE_SOURCES, null, null, MemberRole.STAFF)).hasStatusOk();
        SecurityEnvelopeAssertions.assertEnvelope(
            exchange(HttpMethod.GET, IMAGE_SOURCES + "/IMG-4611686018427387905/inspection", null, null, MemberRole.STAFF).getResponse(),
            SecurityErrorCode.FORBIDDEN);
        verify(useCase, org.mockito.Mockito.never()).inspectImageAdmin(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"products%2F700%2Fa.png", "IMG-0", "DESC-xyz", "IMG-1;x"})
    void malformedSourceIdIsRejectedBeforeUseCase(String sourceId) {
        var result = exchange(HttpMethod.GET, IMAGE_SOURCES + "/" + sourceId + "/inspection", null, null, MemberRole.ADMIN);
        assertThat(result.getResponse().getStatus()).isBetween(400, 404);
        verifyNoInteractions(useCase);
    }

    @Test
    void imageCreatePassesApprovalsAndConfirmation() {
        given(useCase.createAdmin(any())).willReturn(presenter.accepted(info(12L)));
        String body = "{\"templateId\":\"1\",\"mode\":\"IMAGE\",\"personalInfoRemovedConfirmed\":true,"
            + "\"images\":[{\"sourceId\":\"IMG-4611686018427387905\",\"approvedContentHash\":\"" + "a".repeat(64) + "\"}]}";
        assertThat(exchange(HttpMethod.POST, CREATE, body, KEY, MemberRole.ADMIN)).hasStatus(HttpStatus.ACCEPTED);
        verify(useCase).createAdmin(org.mockito.ArgumentMatchers.argThat(c -> "IMAGE".equals(c.mode()) && c.sourceText() == null
            && c.personalInfoRemovedConfirmed() && c.images().size() == 1 && c.images().getFirst().approvedContentHash().equals("a".repeat(64))));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"templateId\":1,\"mode\":\"IMAGE\",\"personalInfoRemovedConfirmed\":true,\"images\":[]}|SIZE_CHART_DRAFT_112",
        "{\"templateId\":1,\"mode\":\"IMAGE\",\"personalInfoRemovedConfirmed\":false,\"images\":[{\"sourceId\":\"IMG-1\",\"approvedContentHash\":\"HASH\"}]}|SIZE_CHART_DRAFT_115",
        "{\"templateId\":1,\"mode\":\"IMAGE\",\"personalInfoRemovedConfirmed\":true,\"images\":[{\"sourceId\":\"IMG-1\",\"approvedContentHash\":\"nothex\"}]}|SIZE_CHART_DRAFT_114",
        "{\"templateId\":1,\"mode\":\"IMAGE\",\"sourceText\":\"S 50\",\"personalInfoRemovedConfirmed\":true,\"images\":[{\"sourceId\":\"IMG-1\",\"approvedContentHash\":\"HASH\"}]}|SIZE_CHART_DRAFT_116",
        "{\"templateId\":1,\"mode\":\"TEXT\",\"sourceText\":\"S 50\",\"images\":[{\"sourceId\":\"IMG-1\",\"approvedContentHash\":\"HASH\"}]}|SIZE_CHART_DRAFT_117",
        "{\"templateId\":1,\"mode\":\"IMAGE\",\"personalInfoRemovedConfirmed\":true,\"images\":[{\"sourceId\":\"IMG-1\",\"approvedContentHash\":\"HASH\"},"
            + "{\"sourceId\":\"IMG-1\",\"approvedContentHash\":\"HASH\"}]}|SIZE_CHART_DRAFT_112",
        "{\"templateId\":1,\"mode\":\"VIDEO\",\"sourceText\":\"S 50\"}|SIZE_CHART_DRAFT_103"})
    void invalidImageCreateBodiesUseFieldCodes(String body, String code) {
        var result = exchange(HttpMethod.POST, CREATE, body.replace("HASH", "a".repeat(64)), KEY, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        verifyNoInteractions(useCase);
    }

    @Test
    void createAcceptsOnlyBoundReceiptAndUsesPrincipalActor() {
        given(useCase.createAdmin(any())).willReturn(presenter.accepted(info(12L)));
        var result = exchange(HttpMethod.POST, CREATE, BODY, KEY, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        assertThat(result).bodyJson().extractingPath("$.dataBody").asMap().hasSize(2)
            .containsEntry("draftId", "9").containsEntry("jobId", "12");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        verify(useCase).createAdmin(org.mockito.ArgumentMatchers.argThat(c -> c.actorId() == TestTokens.MEMBER_ID
            && c.productId() == 700 && c.templateId() == 1 && c.idempotencyKey().toString().equals(KEY)));
    }

    @Test
    void uncertainRegistrationExposesStableLocation() {
        given(useCase.createAdmin(any())).willThrow(new SizeChartDraftUnavailableException(9, SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE));
        assertUncertain(exchange(HttpMethod.POST, CREATE, BODY, KEY, MemberRole.ADMIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "1-1-1-1-1"})
    void malformedKeyIsRejectedBeforeCallingUseCase(String key) {
        var result = exchange(HttpMethod.POST, CREATE, BODY, key, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_DRAFT_111");
        verifyNoInteractions(useCase);
    }

    @Test
    void missingKeyIsAUniformValidationEnvelope() {
        var result = exchange(HttpMethod.POST, CREATE, BODY, null, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_DRAFT_111");
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"templateId\":0,\"mode\":\"TEXT\",\"sourceText\":\"S 50\"}",
        "{\"templateId\":1,\"mode\":\"IMAGE\",\"sourceText\":\"S 50\"}",
        "{\"templateId\":1,\"mode\":\"TEXT\",\"sourceText\":\" \"}", "{"})
    void invalidCreateBodyUsesDraftCodes(String body) {
        var result = exchange(HttpMethod.POST, CREATE, body, KEY, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").asString().startsWith("SIZE_CHART_DRAFT_1");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        verifyNoInteractions(useCase);
    }

    @Test
    void applyValidatesNestedManualChartAndPassesVersionAcknowledgmentsAndPrincipal() {
        given(useCase.applyAdmin(eq(9L), eq(2L), any(), eq(Set.of()), eq(TestTokens.MEMBER_ID)))
            .willReturn(presenter.applied(new SizeChartDraftApplyInfo(9, 700, 80, 4, TestTokens.MEMBER_ID, Instant.now())));
        var result = exchange(HttpMethod.POST, DRAFTS + "/9/apply", APPLY, null, MemberRole.ADMIN);
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.chartId").isEqualTo("80");
        verify(useCase).applyAdmin(eq(9L), eq(2L), any(), eq(Set.of()), eq(TestTokens.MEMBER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedDraftVersion\":-1}",
        "{\"expectedDraftVersion\":1,\"correctedChart\":{\"templateId\":1,\"rows\":[]},\"acknowledgedWarningIds\":[]}"})
    void invalidApplyBodyUsesDraftValidationCodes(String body) {
        var result = exchange(HttpMethod.POST, DRAFTS + "/9/apply", body, null, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").asString().startsWith("SIZE_CHART_DRAFT_1");
        verifyNoInteractions(useCase);
    }

    @Test
    void getRefreshAndListReturnSafeNoStoreViewsAndFilterMeByPrincipal() throws Exception {
        given(useCase.getAdmin(9)).willReturn(presenter.detail(info(12L)));
        given(useCase.refreshAdmin(9, TestTokens.MEMBER_ID)).willReturn(presenter.detail(info(12L)));
        given(useCase.listAdmin(null, TestTokens.MEMBER_ID, 0, 20)).willReturn(com.commercefoundry.persistence.dto.PageResponse.of(Page.empty()));
        for (var result : new MvcTestResult[] {
            exchange(HttpMethod.GET, DRAFTS + "/9", null, null, MemberRole.ADMIN),
            exchange(HttpMethod.POST, DRAFTS + "/9/refresh", null, null, MemberRole.ADMIN),
            exchange(HttpMethod.GET, DRAFTS + "?requestedByMe=true", null, null, MemberRole.ADMIN)}) {
            assertThat(result).hasStatusOk();
            assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("sourceText", "requestHash", "requestedByMemberId", "templateJson");
        }
        verify(useCase).listAdmin(null, TestTokens.MEMBER_ID, 0, 20);
        verify(useCase).refreshAdmin(9, TestTokens.MEMBER_ID);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_STAFF", "ADMIN_CATALOG_READ"})
    void catalogReadCannotRefresh() {
        var result = mvc.post().uri(DRAFTS + "/9/refresh").exchange();
        SecurityEnvelopeAssertions.assertEnvelope(result.getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_STAFF", "ADMIN_CATALOG_READ"})
    void catalogReadCanReadDraft() {
        given(useCase.getAdmin(9)).willReturn(presenter.detail(info(12L)));
        assertThat(mvc.get().uri(DRAFTS + "/9").exchange()).hasStatusOk();
        verify(useCase).getAdmin(9);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_STAFF", "ADMIN_CATALOG_MANAGE"})
    void managePermissionDoesNotImplicitlyGrantRead() {
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(DRAFTS + "/9").exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @Test
    void remoteBodyIsNeverExposed() throws Exception {
        var remote = org.mockito.Mockito.mock(feign.FeignException.class);
        given(remote.getMessage()).willReturn("private-provider-body");
        given(useCase.refreshAdmin(9, TestTokens.MEMBER_ID)).willThrow(remote);
        var result = exchange(HttpMethod.POST, DRAFTS + "/9/refresh", null, null, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_DRAFT_502");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-provider-body");
    }

    @Test
    void expiredDetailReturns410WithoutTemplateOrCandidate() throws Exception {
        given(useCase.getAdmin(9)).willThrow(new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.EXPIRED));
        var result = exchange(HttpMethod.GET, DRAFTS + "/9", null, null, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.GONE);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_DRAFT_005");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("templateJson", "candidateJson", "columns", "rows");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void domainErrorsKeepDraftCodeAndDoNotExposeDetails() {
        given(useCase.getAdmin(9)).willThrow(new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.NOT_FOUND));
        var result = exchange(HttpMethod.GET, DRAFTS + "/9", null, null, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_DRAFT_001");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?productId=0"})
    void invalidListParametersNeverCallUseCase(String query) {
        assertThat(exchange(HttpMethod.GET, DRAFTS + query, null, null, MemberRole.ADMIN)).hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(useCase);
    }

    private void assertUncertain(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SIZE_CHART_DRAFT_501");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(DRAFTS + "/9");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    private SizeChartDraftInfo info(Long jobId) {
        return new SizeChartDraftInfo(9, 700, 1, 1, "{\"unit\":\"CM\",\"columns\":[{\"id\":10,\"name\":\"가슴\"}]}",
            null, null, jobId, SizeChartDraftStatus.REQUESTED, 2, Instant.now().plusSeconds(3600), null);
    }

    private MvcTestResult exchange(HttpMethod method, String path, String body, String key, MemberRole role) {
        var request = mvc.method(method).uri(path);
        if (role != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }
}
