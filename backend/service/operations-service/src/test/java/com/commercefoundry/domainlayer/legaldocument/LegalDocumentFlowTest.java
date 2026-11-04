package com.commercefoundry.domainlayer.legaldocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRepository;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRevisionRepository;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LegalDocumentFlowTest extends ResourceServerSmokeTestSupport {
    private static final String ADMIN = "/api/v1/admin/store/legal-documents";
    private static final String PUBLIC = "/api/v1/store/legal-documents";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired
    LegalDocumentRepository documents;
    @Autowired
    LegalDocumentRevisionRepository revisions;

    @BeforeEach
    void clear() {
        revisions.deleteAll();
        documents.deleteAll();
    }

    @Test
    void actualJwtProtectsAllAdminReadsAndWrites() throws Exception {
        for (String path : new String[]{ADMIN, ADMIN + "/TERMS/ko", ADMIN + "/TERMS/ko/revisions",
            "/api/v1/admin/store/legal-document-templates/TERMS?locale=ko"}) {
            assertThat(perform(get(path)).getStatus()).isEqualTo(401);
            assertThat(perform(get(path).header("Authorization", bearer(MemberRole.CUSTOMER))).getStatus()).isEqualTo(403);
        }
        for (var write : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
            put(ADMIN + "/TERMS/ko/draft"), post(ADMIN + "/TERMS/ko/publish")}) {
            assertThat(perform(write.contentType(MediaType.APPLICATION_JSON).content("{}")).getStatus()).isEqualTo(401);
            assertThat(perform(write.header("Authorization", bearer(MemberRole.CUSTOMER))).getStatus()).isEqualTo(403);
        }
        assertThat(data(perform(get(PUBLIC))).isArray()).isTrue();
        assertThat(perform(get(PUBLIC + "/TERMS")).getStatus()).isEqualTo(404);
    }

    @Test
    void draftIsPrivatePublishedAllowListAndNewDraftKeepsPublicHistory() throws Exception {
        var first = save(null, "First title", "First body");
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(data(first).get("document").get("version").longValue()).isZero();
        assertThat(perform(get(PUBLIC + "/TERMS")).getStatus()).isEqualTo(404);
        assertThat(perform(get(PUBLIC + "/TERMS/revisions/1")).getStatus()).isEqualTo(404);
        var published = publish(0L, 1, true);
        assertThat(published.getStatus()).isEqualTo(200);
        var currentResponse = perform(get(PUBLIC + "/TERMS?locale=ko"));
        assertThat(currentResponse.getHeader("Cache-Control")).contains("no-store");
        var current = data(currentResponse);
        assertThat(current.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
            "type", "locale", "revisionNumber", "title", "body", "publishedAt");
        assertThat(current.get("body").stringValue()).isEqualTo("First body");
        assertThat(data(perform(get(PUBLIC))).get(0).properties()).extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("type", "locale", "revisionNumber", "title", "publishedAt");
        assertThat(data(adminGet(ADMIN)).get(0).properties()).extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("type", "locale", "latestRevision", "publishedRevision", "version");
        assertThat(save(1L, "Second title", "Secret draft").getStatus()).isEqualTo(200);
        assertThat(data(perform(get(PUBLIC + "/TERMS"))).get("body").stringValue()).isEqualTo("First body");
        assertThat(perform(get(PUBLIC + "/TERMS/revisions/2")).getStatus()).isEqualTo(404);
        assertThat(perform(get(PUBLIC + "/TERMS?locale=en")).getStatus()).isEqualTo(404);
        var beforeRetry = data(adminGet(ADMIN + "/TERMS/ko"));
        var retry = publish(0L, 1, true);
        assertThat(retry.getStatus()).isEqualTo(200);
        assertThat(data(retry)).isEqualTo(beforeRetry);
        assertThat(data(adminGet(ADMIN + "/TERMS/ko"))).isEqualTo(beforeRetry);
        assertThat(data(retry).get("revision").get("body").stringValue()).isEqualTo("Secret draft");
        assertThat(data(perform(get(PUBLIC + "/TERMS")))).isEqualTo(current);
        assertThat(publish(1L, 2, true).getStatus()).isEqualTo(409);
        assertThat(publish(2L, 2, true).getStatus()).isEqualTo(200);
        assertThat(data(perform(get(PUBLIC + "/TERMS/revisions/1"))).get("body").stringValue()).isEqualTo("First body");
        var history = data(adminGet(ADMIN + "/TERMS/ko/revisions?page=0&size=1"));
        assertThat(history.get("totalElements").longValue()).isEqualTo(2);
        assertThat(history.get("contents").get(0).get("createdBy").isString()).isTrue();
    }

    @Test
    void validatesBodyTitleControlsReviewAndPaginationWithConsistentEnvelope() throws Exception {
        for (String bad : new String[]{draft(null, "", "body"), draft(-1L, "title", "body"), draft(null, "t".repeat(201), "body"),
            draft(null, "title", "b".repeat(50001)), draft(null, "title", "a" + (char) 0 + "b"),
            draft(null, "a\nb", "body"), "{"}) {
            var response = adminPut(bad);
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("LEGAL_DOCUMENT_1");
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
        }
        assertThat(documents.count()).isZero();
        assertThat(save(null, "t".repeat(200), "First line\n\tSecond line\r\n<script>plain text</script>").getStatus()).isEqualTo(200);
        assertThat(publish(0L, 1, false).getStatus()).isEqualTo(400);
        assertThat(adminGet(ADMIN + "/TERMS/ko/revisions?size=21").getStatus()).isEqualTo(400);
        assertThat(adminGet(ADMIN + "/TERMS/ko/revisions?page=-1").getStatus()).isEqualTo(400);
        assertThat(perform(get(PUBLIC + "/TERMS?locale=KO")).getStatus()).isEqualTo(400);
        assertThat(perform(get(PUBLIC + "/INVALID")).getStatus()).isEqualTo(400);
        assertThat(perform(get(PUBLIC + "/TERMS/revisions/0")).getStatus()).isEqualTo(400);
        assertThat(publish(0L, 1, true).getStatus()).isEqualTo(200);
        assertThat(data(perform(get(PUBLIC + "/TERMS"))).get("body").stringValue()).contains("<script>plain text</script>");
    }

    @Test
    void rejectsMissingPublishFieldsAndInvalidParameterTypesWithoutWriting() throws Exception {
        assertThat(save(null, "Valid title", "Valid body").getStatus()).isEqualTo(200);
        for (String body : new String[]{"{}", "{\"expectedVersion\":0,\"revisionNumber\":1}",
            "{\"expectedVersion\":0,\"revisionNumber\":1,\"reviewConfirmed\":null}",
            "{\"revisionNumber\":1,\"reviewConfirmed\":true}",
            "{\"expectedVersion\":0,\"revisionNumber\":0,\"reviewConfirmed\":true}"}) {
            var response = perform(post(ADMIN + "/TERMS/ko/publish").header("Authorization", bearer(MemberRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(body));
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("LEGAL_DOCUMENT_1");
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
        }
        assertThat(adminGet(ADMIN + "/TERMS/ko/revisions?page=invalid").getContentAsString()).contains("LEGAL_DOCUMENT_198");
        assertThat(perform(get(PUBLIC + "/TERMS/revisions/invalid")).getContentAsString()).contains("LEGAL_DOCUMENT_198");
        assertThat(data(adminGet(ADMIN + "/TERMS/ko")).get("document").get("version").longValue()).isZero();
        assertThat(data(perform(get(PUBLIC))).size()).isZero();
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void templatesAreAdminOnlyOriginalStructuresAndNeverPersistOrPublish() throws Exception {
        for (String type : new String[]{"TERMS", "PRIVACY"}) {
            for (String locale : new String[]{"ko", "en"}) {
                var response = adminGet("/api/v1/admin/store/legal-document-templates/" + type + "?locale=" + locale);
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.getHeader("Cache-Control")).contains("no-store");
                var template = data(response);
                assertThat(template.get("body").stringValue()).contains("{{COMPANY_NAME}}");
                assertThat(template.get("checklist").size()).isEqualTo(4);
                assertThat(template.get("version").intValue()).isEqualTo(1);
            }
        }
        assertThat(documents.count()).isZero();
        assertThat(revisions.count()).isZero();
        assertThat(save(null, "Unresolved", "Body {{COMPANY_NAME}}").getStatus()).isEqualTo(200);
        var blocked = publish(0L, 1, true);
        assertThat(blocked.getStatus()).isEqualTo(400);
        assertThat(blocked.getContentAsString()).contains("LEGAL_DOCUMENT_022");
        assertThat(perform(get(PUBLIC + "/TERMS")).getStatus()).isEqualTo(404);
    }

    @Test
    void auditUsesActualActorAndFixedTargetWithoutDocumentText() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(save(null, "Private title", "Private body").getStatus()).isEqualTo(200);
            assertThat(publish(0L, 1, true).getStatus()).isEqualTo(200);
            assertThat(save(0L, "Other title", "Other body").getStatus()).isEqualTo(409);
            String actor = " actorMemberId=" + TestTokens.MEMBER_ID + " target=legal-document:terms-ko result=";
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=LEGAL_DOCUMENT_DRAFT_SAVE" + actor + "SUCCESS", "action=LEGAL_DOCUMENT_PUBLISH" + actor + "SUCCESS",
                "action=LEGAL_DOCUMENT_DRAFT_SAVE" + actor + "FAILURE");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private MockHttpServletResponse adminGet(String path) throws Exception {
        return perform(get(path).header("Authorization", bearer(MemberRole.ADMIN)));
    }

    private MockHttpServletResponse adminPut(String body) throws Exception {
        return perform(put(ADMIN + "/TERMS/ko/draft").header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MockHttpServletResponse save(Long expected, String title, String body) throws Exception {
        return adminPut(draft(expected, title, body));
    }

    private String draft(Long version, String title, String body) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("expectedVersion", version);
        values.put("title", title);
        values.put("body", body);
        return JSON.writeValueAsString(values);
    }

    private MockHttpServletResponse publish(Long version, int revisionNumber, boolean reviewed) throws Exception {
        return perform(post(ADMIN + "/TERMS/ko/publish").header("Authorization", bearer(MemberRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of(
                "expectedVersion", version, "revisionNumber", revisionNumber, "reviewConfirmed", reviewed))));
    }

    private JsonNode data(MockHttpServletResponse response) throws java.io.UnsupportedEncodingException {
        return JSON.readTree(response.getContentAsString()).get("dataBody");
    }

}
