package com.commercefoundry.domainlayer.knowledge.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.presenter.KnowledgeAdminPresenter;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeAdminInfo;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeSummaryInfo;
import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeAdminQuery;
import com.commercefoundry.domainlayer.knowledge.application.port.in.KnowledgeAdminUseCase;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeStatus;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(KnowledgeAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class KnowledgeAdminControllerTest {

    private static final String PATH = "/api/v1/admin/support/knowledge";
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final long ID = 4_611_686_018_427_387_904L;
    private static final String CONTENT = """
        {"title":"Shipping","body":"Private policy text","category":"SHIPPING","validFrom":"2026-11-02T00:00:00Z"}
        """.strip();
    private static final String CREATE = "{\"type\":\"FAQ\",\"locale\":\"KO\",\"content\":" + CONTENT + "}";
    private static final String EDIT = "{\"revisionNumber\":1,\"expectedVersion\":4,\"content\":" + CONTENT + "}";
    private static final String DRAFT = "{\"expectedDocumentVersion\":3,\"content\":" + CONTENT + "}";
    private static final String APPROVE = "{\"revisionNumber\":1,\"expectedVersion\":4}";
    private static final String PUBLISH = "{\"revisionNumber\":1,\"expectedVersion\":4,\"expectedDocumentVersion\":3}";
    private static final String ARCHIVE_QUERY = "/101?revisionNumber=1&expectedVersion=4&expectedDocumentVersion=3";

    @Autowired MockMvcTester mvc;
    @MockitoBean KnowledgeAdminUseCase useCase;

    @ParameterizedTest
    @CsvSource({"GET, ''", "GET, /101", "POST, ''", "PUT, /101", "DELETE, /101",
        "POST, /101/revisions", "POST, /101/approve", "POST, /101/publish"})
    void everyHandlerRejectsAnonymousAndCustomer(String method, String suffix) {
        assertThat(exchange(HttpMethod.valueOf(method), suffix, CREATE, null)).hasStatus(401);
        assertThat(exchange(HttpMethod.valueOf(method), suffix, CREATE, MemberRole.CUSTOMER)).hasStatus(403);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF"})
    @DisplayName("목록 · 상세는 CS_READ 라 MANAGER · STAFF 도 200 이다 (#588)")
    void csReadersMayRead(MemberRole role) {
        assertThat(exchange(HttpMethod.GET, "", null, role)).hasStatusOk();
        assertThat(exchange(HttpMethod.GET, "/101", null, role)).hasStatusOk();
    }

    @Test
    @DisplayName("생성 · 편집 · 새 초안은 CS_MANAGE 라 MANAGER 는 유스케이스에 닿고 작성자는 토큰의 memberId 다 (#588)")
    void managerMayDraft() {
        assertThat(exchange(HttpMethod.POST, "", CREATE, MemberRole.MANAGER)).hasStatus(201);
        assertThat(exchange(HttpMethod.PUT, "/101", EDIT, MemberRole.MANAGER)).hasStatusOk();
        assertThat(exchange(HttpMethod.POST, "/101/revisions", DRAFT, MemberRole.MANAGER)).hasStatus(201);

        verify(useCase).create(any(), eq(TestTokens.MEMBER_ID));
        verify(useCase).edit(eq(101L), eq(1), eq(4L), any(), eq(TestTokens.MEMBER_ID));
        verify(useCase).createDraft(eq(101L), eq(3L), any(), eq(TestTokens.MEMBER_ID));
    }

    @Test
    @DisplayName("STAFF 는 CS_READ 뿐이라 생성 · 편집 · 새 초안(유효한 본문)이 403 · SECURITY_005 이고 유스케이스를 부르지 않는다 (#588)")
    void staffMayNotDraft() {
        SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.POST, "", CREATE, MemberRole.STAFF).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.PUT, "/101", EDIT, MemberRole.STAFF).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(
            exchange(HttpMethod.POST, "/101/revisions", DRAFT, MemberRole.STAFF).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"MANAGER", "STAFF"})
    @DisplayName("승인 · 게시 · 보관은 고객 AI 응답에 나가는 결정이라 SETTINGS_MANAGE — MANAGER · STAFF 는 유효한 요청이어도 403 · SECURITY_005 다 (#588)")
    void nonOwnersMayNotApprovePublishOrArchive(MemberRole role) {
        SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.POST, "/101/approve", APPROVE, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.POST, "/101/publish", PUBLISH, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.DELETE, ARCHIVE_QUERY, null, role).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("OWNER 는 SETTINGS_MANAGE 가 있어 승인 · 게시 · 보관이 유스케이스에 닿는다 (#588)")
    void ownerMayApprovePublishAndArchive() {
        assertThat(exchange(HttpMethod.POST, "/101/approve", APPROVE, MemberRole.OWNER)).hasStatusOk();
        assertThat(exchange(HttpMethod.POST, "/101/publish", PUBLISH, MemberRole.OWNER)).hasStatusOk();
        assertThat(exchange(HttpMethod.DELETE, ARCHIVE_QUERY, null, MemberRole.OWNER)).hasStatusOk();

        verify(useCase).approve(101, 1, 4L, TestTokens.MEMBER_ID);
        verify(useCase).publish(101, 1, 4L, 3L, TestTokens.MEMBER_ID);
        verify(useCase).archive(101, 1, 4L, 3L, TestTokens.MEMBER_ID);
    }

    @Test
    void createReturns201MetadataStringIdentifiersAndVersionsAndUsesTokenActor() {
        given(useCase.create(any(), eq(TestTokens.MEMBER_ID))).willReturn(new KnowledgeAdminPresenter().toResponse(info()));
        var result = exchange(HttpMethod.POST, "", CREATE, MemberRole.ADMIN);
        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.dataBody.documentId").isEqualTo(Long.toString(ID));
        assertThat(result).bodyJson().extractingPath("$.dataBody.revisionId").isEqualTo(Long.toString(ID + 1));
        assertThat(result).bodyJson().extractingPath("$.dataBody.type.code").isEqualTo("FAQ");
        assertThat(result).bodyJson().extractingPath("$.dataBody.locale.code").isEqualTo("KO");
        assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("DRAFT");
        assertThat(result).bodyJson().extractingPath("$.dataBody.documentVersion").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.dataBody.revisionVersion").isEqualTo(4);
    }

    @Test
    void listDefaultsFiltersPaginationAndSummaryExcludeBody() {
        KnowledgeSummaryInfo summary = new KnowledgeSummaryInfo(ID, KnowledgeType.FAQ, KnowledgeLocale.KO, 1, null, 3L, 4L,
            "Shipping", KnowledgeCategory.SHIPPING, KnowledgeStatus.DRAFT, NOW, null, null, null, null);
        given(useCase.list(any())).willReturn(new KnowledgeAdminPresenter().toPage(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1)));
        var result = exchange(HttpMethod.GET, "?type=FAQ&locale=KO", null, MemberRole.ADMIN);
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].documentId").isEqualTo(Long.toString(ID));
        assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0]").asMap().doesNotContainKey("body");
        assertThat(result).bodyJson().extractingPath("$.dataBody.totalElements").isEqualTo(1);
        verify(useCase).list(new KnowledgeAdminQuery(KnowledgeType.FAQ, KnowledgeLocale.KO, 0, 20));
    }

    @ParameterizedTest
    @CsvSource({"?page=-1, SUPPORT_113, page", "?size=0, SUPPORT_114, size", "?size=101, SUPPORT_114, size",
        "?type=OTHER, SUPPORT_198, type", "?locale=OTHER, SUPPORT_198, locale", "?page=oops, SUPPORT_198, page"})
    void invalidListArgumentsReturnFieldEnvelope(String query, String code, String field) {
        assertField(exchange(HttpMethod.GET, query, null, MemberRole.ADMIN), code, field);
        verifyNoInteractions(useCase);
    }

    @Test
    void contentFieldCodesAndCrossFieldPeriodAreReturned() {
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("\"Shipping\"", "\" \""), MemberRole.ADMIN), "SUPPORT_104", "content.title");
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("Private policy text", " "), MemberRole.ADMIN), "SUPPORT_105", "content.body");
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("\"category\":\"SHIPPING\"", "\"category\":null"), MemberRole.ADMIN),
            "SUPPORT_106", "content.category");
        String invalidPeriod = CREATE.replace("\"validFrom\":", "\"validTo\":\"2026-11-02T00:00:00Z\",\"validFrom\":");
        assertField(exchange(HttpMethod.POST, "", invalidPeriod, MemberRole.ADMIN), "SUPPORT_108", "content.periodValid");
        verifyNoInteractions(useCase);
    }

    @Test
    void requiredContentTypeLocaleAndStartTimeHaveTheirOwnCodes() {
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("\"type\":\"FAQ\"", "\"type\":null"), MemberRole.ADMIN), "SUPPORT_101", "type");
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("\"locale\":\"KO\"", "\"locale\":null"), MemberRole.ADMIN), "SUPPORT_102", "locale");
        assertField(exchange(HttpMethod.POST, "", "{\"type\":\"FAQ\",\"locale\":\"KO\"}", MemberRole.ADMIN), "SUPPORT_103", "content");
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("\"2026-11-02T00:00:00Z\"", "null"), MemberRole.ADMIN),
            "SUPPORT_107", "content.validFrom");
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("Shipping", "x".repeat(201)), MemberRole.ADMIN), "SUPPORT_104", "content.title");
        assertField(exchange(HttpMethod.POST, "", CREATE.replace("Private policy text", "x".repeat(8001)), MemberRole.ADMIN), "SUPPORT_105", "content.body");
        verifyNoInteractions(useCase);
    }

    @Test
    void putValidationPreservesContentFieldInsteadOfRequestName() {
        String body = "{\"revisionNumber\":1,\"expectedVersion\":4,\"content\":" + CONTENT.replace("Shipping", " ") + "}";
        assertField(exchange(HttpMethod.PUT, "/101", body, MemberRole.ADMIN), "SUPPORT_104", "content.title");
        verifyNoInteractions(useCase);
    }

    @Test
    void requiredVersionAndDeleteQueryContract() {
        assertField(exchange(HttpMethod.POST, "/101/publish", "{\"revisionNumber\":1,\"expectedVersion\":0}", MemberRole.ADMIN),
            "SUPPORT_111", "expectedDocumentVersion");
        assertField(exchange(HttpMethod.DELETE, "/101?revisionNumber=1&expectedVersion=0", null, MemberRole.ADMIN),
            "SUPPORT_199", "expectedDocumentVersion");
        assertThat(exchange(HttpMethod.DELETE, "/101?revisionNumber=1&expectedVersion=4&expectedDocumentVersion=3", null, MemberRole.ADMIN)).hasStatusOk();
        verify(useCase).archive(101, 1, 4L, 3L, TestTokens.MEMBER_ID);
    }

    @Test
    void adminReadsAndAllOtherWritesAreAcceptedAndCarryAuthenticatedActor() {
        assertThat(exchange(HttpMethod.GET, "/101", null, MemberRole.ADMIN)).hasStatusOk();
        assertThat(exchange(HttpMethod.PUT, "/101", "{\"revisionNumber\":1,\"expectedVersion\":4,\"content\":" + CONTENT + "}", MemberRole.ADMIN))
            .hasStatusOk();
        assertThat(exchange(HttpMethod.POST, "/101/revisions", "{\"expectedDocumentVersion\":3,\"content\":" + CONTENT + "}", MemberRole.ADMIN))
            .hasStatus(201);
        assertThat(exchange(HttpMethod.POST, "/101/approve", "{\"revisionNumber\":1,\"expectedVersion\":4,\"actorId\":999}", MemberRole.ADMIN)).hasStatusOk();
        assertThat(exchange(HttpMethod.POST, "/101/publish",
            "{\"revisionNumber\":1,\"expectedVersion\":4,\"expectedDocumentVersion\":3}", MemberRole.ADMIN)).hasStatusOk();
        verify(useCase).approve(101, 1, 4L, TestTokens.MEMBER_ID);
        verify(useCase).publish(101, 1, 4L, 3L, TestTokens.MEMBER_ID);
    }

    private KnowledgeAdminInfo info() {
        return new KnowledgeAdminInfo(new KnowledgeDocument(ID, KnowledgeType.FAQ, KnowledgeLocale.KO, 1, null, 3L),
            new KnowledgeRevision(ID + 1, ID, 1, new KnowledgeRevision.Content("Shipping", "Private policy text", KnowledgeCategory.SHIPPING, NOW, null),
                KnowledgeStatus.DRAFT, null, null, null, 4L));
    }

    private MvcTestResult exchange(HttpMethod method, String suffix, String body, MemberRole role) {
        var request = mvc.method(method).uri(PATH + suffix);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        if (body != null && (method == HttpMethod.POST || method == HttpMethod.PUT)) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private void assertField(MvcTestResult result, String code, String field) {
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }
}
