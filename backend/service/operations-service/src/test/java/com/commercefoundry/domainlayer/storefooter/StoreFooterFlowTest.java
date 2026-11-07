package com.commercefoundry.domainlayer.storefooter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.storefooter.adapter.in.admin.presenter.StoreFooterAdminPresenter;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterLinkRepository;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterRepository;
import com.commercefoundry.domainlayer.storefooter.adapter.out.persistence.repository.StoreFooterRevisionRepository;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:store-footer-flow-597;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "store.footer.allowed-external-hosts=links.example.com"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreFooterFlowTest extends ResourceServerSmokeTestSupport {
    private static final String ADMIN = "/api/v1/admin/store/footer";
    private static final String PUBLIC = "/api/v1/store/footer";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired StoreFooterRepository footers;
    @Autowired StoreFooterRevisionRepository revisions;
    @Autowired StoreFooterLinkRepository links;
    @MockitoSpyBean StoreFooterAdminPresenter presenter;

    @BeforeEach
    void clear() {
        links.deleteAll();
        revisions.deleteAll();
        footers.deleteAll();
    }

    @Test
    void actualJwtEnforcesReadAndManageAuthorities() throws Exception {
        for (String path : List.of(ADMIN, ADMIN + "/revisions")) {
            assertThat(perform(get(path)).getStatus()).isEqualTo(401);
            assertThat(request(get(path), null, MemberRole.CUSTOMER).getStatus()).isEqualTo(403);
        }
        for (MemberRole role : List.of(MemberRole.CUSTOMER, MemberRole.MANAGER, MemberRole.STAFF)) {
            assertThat(request(get(ADMIN), null, role).getStatus()).isEqualTo(403);
            assertThat(request(get(ADMIN + "/revisions"), null, role).getStatus()).isEqualTo(403);
            assertThat(request(put(ADMIN), draft(null, "Example"), role).getStatus()).isEqualTo(403);
            assertThat(request(post(ADMIN + "/publication"), publication(0, 1), role).getStatus()).isEqualTo(403);
        }
        assertThat(perform(put(ADMIN).contentType(MediaType.APPLICATION_JSON).content(draft(null, "Example"))).getStatus()).isEqualTo(401);
        assertThat(perform(post(ADMIN + "/publication").contentType(MediaType.APPLICATION_JSON).content(publication(0, 1))).getStatus()).isEqualTo(401);
        assertThat(request(put(ADMIN), draft(null, "Example"), MemberRole.OWNER).getStatus()).isEqualTo(200);
        for (MemberRole role : List.of(MemberRole.ADMIN, MemberRole.OWNER)) {
            assertThat(request(get(ADMIN), null, role).getStatus()).isEqualTo(200);
            assertThat(request(get(ADMIN + "/revisions"), null, role).getStatus()).isEqualTo(200);
        }
        assertThat(publish(0, 1).getStatus()).isEqualTo(200);
    }

    @Test
    void draftsStayPrivateAndPublicFieldsAreLocalizedFilteredAndOrdered() throws Exception {
        error(perform(get(PUBLIC)), 404, "STORE_FOOTER_001");
        error(adminGet(ADMIN), 404, "STORE_FOOTER_001");
        var saved = save(null, "First");
        assertThat(saved.getStatus()).isEqualTo(200);
        assertThat(saved.getHeader("Cache-Control")).contains("no-store");
        var detail = data(saved);
        assertThat(detail.get("version").longValue()).isZero();
        assertThat(detail.get("revision").get("createdBy").stringValue()).isEqualTo(Long.toString(TestTokens.MEMBER_ID));
        error(perform(get(PUBLIC)), 404, "STORE_FOOTER_001");
        assertThat(publish(0, 1).getStatus()).isEqualTo(200);
        var publicResponse = perform(get(PUBLIC));
        assertThat(publicResponse.getHeader("Cache-Control")).contains("no-store");
        var ko = data(publicResponse);
        assertThat(ko.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("copyrightText", "links");
        assertThat(ko.get("copyrightText").stringValue()).isEqualTo("국문 예시 First");
        assertThat(ko.get("links").size()).isEqualTo(2);
        assertThat(ko.get("links").get(0).get("label").stringValue()).isEqualTo("먼저 First");
        assertThat(ko.get("links").get(1).get("label").stringValue()).isEqualTo("나중 First");
        for (JsonNode link : ko.get("links")) {
            assertThat(link.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("type", "label", "url");
        }
        var en = data(perform(get(PUBLIC + "?locale=en")));
        assertThat(en.get("copyrightText").stringValue()).isEqualTo("English example First");
        assertThat(en.get("links").get(0).get("label").stringValue()).isEqualTo("First First");
        assertThat(en.toString()).doesNotContain("국문", "숨김", "Hidden", "createdBy", "revisionNumber", "version");
        assertThat(save(1L, "Private replacement").getStatus()).isEqualTo(200);
        assertThat(data(perform(get(PUBLIC)))).isEqualTo(ko);
        var latest = data(adminGet(ADMIN));
        assertThat(latest.get("revision").get("publishedAt").isNull()).isTrue();
        assertThat(data(publish(0, 1))).isEqualTo(latest);
        assertThat(data(adminGet(ADMIN))).isEqualTo(latest);
        error(publish(1, 2), 409, "STORE_FOOTER_020");
        assertThat(publish(2, 2).getStatus()).isEqualTo(200);
        error(publish(3, 1), 409, "STORE_FOOTER_020");
        var history = data(adminGet(ADMIN + "/revisions?page=0&size=1"));
        assertThat(history.get("totalElements").longValue()).isEqualTo(2);
        assertThat(history.get("contents").get(0).get("revisionNumber").intValue()).isEqualTo(2);
        assertThat(perform(get(PUBLIC + "/revisions/1")).getStatus()).isEqualTo(404);
    }

    @Test
    void emptyPublishedFooterDoesNotFallbackToOtherLanguageOrOldLinks() throws Exception {
        String body = "{\"expectedVersion\":null,\"copyrightText\":{\"ko\":\"국문 예시\",\"en\":null},\"links\":[]}";
        assertThat(request(put(ADMIN), body, MemberRole.ADMIN).getStatus()).isEqualTo(200);
        assertThat(publish(0, 1).getStatus()).isEqualTo(200);
        var en = data(perform(get(PUBLIC + "?locale=en")));
        assertThat(en.get("copyrightText").isNull()).isTrue();
        assertThat(en.get("links").size()).isZero();
    }

    @Test
    void invalidRequestsAndVersionConflictsKeepConsistentEnvelopeWithoutPartialWrites() throws Exception {
        for (String body : List.of("{", "{}", draft(-1L, "Example"), draft(null, "Example").replace("CUSTOM", "custom"),
            draft(null, "Example").replace("https://links.example.com/hidden", "https://links.example.com/%2fhidden"),
            draft(null, "Example").replace("https://links.example.com/hidden", "https://unapproved.example.com/hidden"),
            draft(null, "Example").replace("\"en\":\"Hidden Example\"", "\"en\":null"),
            draft(null, "Example").replace("\"displayOrder\":10", "\"displayOrder\":20"),
            "{\"copyrightText\":{},\"links\":null}", "{\"copyrightText\":{},\"links\":[null]}")) {
            error(request(put(ADMIN), body, MemberRole.ADMIN), 400, "STORE_FOOTER_1");
        }
        assertThat(footers.count()).isZero();
        assertThat(save(null, "Example").getStatus()).isEqualTo(200);
        error(save(null, "Duplicate"), 409, "STORE_FOOTER_020");
        error(save(9L, "Stale"), 409, "STORE_FOOTER_020");
        for (String body : List.of("{}", "{\"expectedVersion\":0}", "{\"revisionNumber\":1}",
            "{\"expectedVersion\":-1,\"revisionNumber\":1}", "{\"expectedVersion\":0,\"revisionNumber\":0}")) {
            error(request(post(ADMIN + "/publication"), body, MemberRole.ADMIN), 400, "STORE_FOOTER_1");
        }
        for (String locale : List.of("KO", "en-US", "fr", "")) {
            error(perform(get(PUBLIC).param("locale", locale)), 400, "STORE_FOOTER_100");
        }
        for (String query : List.of("size=0", "size=21", "page=-1", "page=invalid")) {
            error(adminGet(ADMIN + "/revisions?" + query), 400, "STORE_FOOTER_1");
        }
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(data(adminGet(ADMIN)).get("version").longValue()).isZero();
        error(perform(get(PUBLIC)), 404, "STORE_FOOTER_001");
    }

    @Test
    void auditContainsOnlyJwtActorFixedTargetActionAndOutcome() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(save(null, "PRIVATE_SENTINEL").getStatus()).isEqualTo(200);
            assertThat(publish(0, 1).getStatus()).isEqualTo(200);
            assertThat(save(0L, "PRIVATE_SENTINEL").getStatus()).isEqualTo(409);
            assertThat(publish(0, 2).getStatus()).isEqualTo(409);
            String actor = " actorMemberId=" + TestTokens.MEMBER_ID + " target=store-footer:1 result=";
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=STORE_FOOTER_DRAFT_SAVE" + actor + "SUCCESS", "action=STORE_FOOTER_PUBLISH" + actor + "SUCCESS",
                "action=STORE_FOOTER_DRAFT_SAVE" + actor + "FAILURE", "action=STORE_FOOTER_PUBLISH" + actor + "FAILURE");
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_SENTINEL", "https://", "labels", "copyrightText");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void auditWriterFailureDoesNotChangeCommittedHttpResultOrConflict() throws Exception {
        try (var writer = mockStatic(AdminAuditLog.class)) {
            writer.when(() -> AdminAuditLog.write(anyString(), any(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("AUDIT_SENTINEL"));
            assertThat(save(null, "Example").getStatus()).isEqualTo(200);
            assertThat(publish(0, 1).getStatus()).isEqualTo(200);
            error(save(0L, "Stale"), 409, "STORE_FOOTER_020");
            assertThat(data(perform(get(PUBLIC))).get("copyrightText").stringValue()).isEqualTo("국문 예시 Example");
            assertThat(revisions.count()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void presenterFailureAfterCommitAuditsFailureWhileCommittedStateRemainsReadable(boolean publishing) throws Exception {
        if (publishing) {
            assertThat(save(null, "Committed").getStatus()).isEqualTo(200);
        }
        doThrow(new IllegalStateException("PRESENTER_SENTINEL")).when(presenter).detail(any());
        try (var writer = mockStatic(AdminAuditLog.class)) {
            var response = publishing ? publish(0, 1) : save(null, "Committed");
            assertThat(response.getStatus()).isEqualTo(500);
            String action = publishing ? "STORE_FOOTER_PUBLISH" : "STORE_FOOTER_DRAFT_SAVE";
            writer.verify(() -> AdminAuditLog.write(action, TestTokens.MEMBER_ID, "store-footer:1", false));
            writer.verifyNoMoreInteractions();
            assertThat(footers.count()).isEqualTo(1);
            assertThat(revisions.count()).isEqualTo(1);
            assertThat(perform(get(PUBLIC)).getStatus()).isEqualTo(publishing ? 200 : 404);
        }
    }

    private MockHttpServletResponse request(MockHttpServletRequestBuilder request, String body, MemberRole role) throws Exception {
        request.header("Authorization", bearer(role));
        return perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MockHttpServletResponse adminGet(String path) throws Exception {
        return request(get(path), null, MemberRole.ADMIN);
    }

    private MockHttpServletResponse save(Long version, String text) throws Exception {
        return request(put(ADMIN), draft(version, text), MemberRole.ADMIN);
    }

    private MockHttpServletResponse publish(long version, int revision) throws Exception {
        return request(post(ADMIN + "/publication"), publication(version, revision), MemberRole.ADMIN);
    }

    private String publication(long version, int revision) {
        return JSON.writeValueAsString(Map.of("expectedVersion", version, "revisionNumber", revision));
    }

    private String draft(Long version, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", version);
        body.put("copyrightText", Map.of("ko", "국문 예시 " + text, "en", "English example " + text));
        body.put("links", List.of(link("나중 " + text, "Later " + text, "later", 20, true),
            link("숨김 " + text, "Hidden " + text, "hidden", 0, false), link("먼저 " + text, "First " + text, "first", 10, true)));
        return JSON.writeValueAsString(body);
    }

    private Map<String, Object> link(String ko, String en, String path, int order, boolean visible) {
        return Map.of("type", "CUSTOM", "labels", Map.of("ko", ko, "en", en), "url", "https://links.example.com/" + path,
            "displayOrder", order, "visible", visible);
    }

    private JsonNode data(MockHttpServletResponse response) throws java.io.UnsupportedEncodingException {
        return JSON.readTree(response.getContentAsString()).get("dataBody");
    }

    private void error(MockHttpServletResponse response, int status, String code) throws java.io.UnsupportedEncodingException {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(JSON.readTree(response.getContentAsString()).has("dataHeader")).isTrue();
        assertThat(response.getContentAsString()).contains(code);
    }
}
