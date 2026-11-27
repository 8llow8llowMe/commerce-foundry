package com.commercefoundry.domainlayer.storenavigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.presenter.StoreNavigationAdminPresenter;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationItemRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRepository;
import com.commercefoundry.domainlayer.storenavigation.adapter.out.persistence.repository.StoreNavigationRevisionRepository;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    "spring.datasource.url=jdbc:h2:mem:store-navigation-flow-599;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "commerce.store.navigation.allowed-external-hosts=links.example.com"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreNavigationFlowTest extends ResourceServerSmokeTestSupport {
    private static final String ADMIN = "/api/v1/admin/store/navigation";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired StoreNavigationRepository navigations;
    @Autowired StoreNavigationRevisionRepository revisions;
    @Autowired StoreNavigationItemRepository items;
    @MockitoSpyBean StoreNavigationAdminPresenter presenter;

    @BeforeEach
    void clear() {
        items.deleteAll();
        revisions.deleteAll();
        navigations.deleteAll();
    }

    @Test
    void actualJwtEnforcesReadAndManageAuthorities() throws Exception {
        assertThat(perform(get(ADMIN)).getStatus()).isEqualTo(401);
        assertThat(perform(put(ADMIN).contentType(MediaType.APPLICATION_JSON).content(draft(null, "Example"))).getStatus()).isEqualTo(401);
        for (MemberRole role : List.of(MemberRole.CUSTOMER, MemberRole.MANAGER, MemberRole.STAFF)) {
            assertThat(request(get(ADMIN), null, role).getStatus()).isEqualTo(403);
            assertThat(request(put(ADMIN), draft(null, "Example"), role).getStatus()).isEqualTo(403);
        }
        assertThat(navigations.count()).isZero();
        assertThat(request(put(ADMIN), draft(null, "Example"), MemberRole.OWNER).getStatus()).isEqualTo(200);
        for (MemberRole role : List.of(MemberRole.ADMIN, MemberRole.OWNER)) {
            assertThat(request(get(ADMIN), null, role).getStatus()).isEqualTo(200);
        }
        assertThat(save(0L, "Admin").getStatus()).isEqualTo(200);
    }

    @Test
    void savedDraftRoundTripsAsTreeOrderedFlatListWithStringIdentifiers() throws Exception {
        error(adminGet(), 404, "STORE_NAV_001");
        var saved = save(null, "First");
        assertThat(saved.getStatus()).isEqualTo(200);
        assertThat(saved.getHeader("Cache-Control")).contains("no-store");
        var detail = data(saved);
        assertThat(detail.properties()).extracting(Map.Entry::getKey)
            .containsExactlyInAnyOrder("version", "latestRevision", "publishedRevision", "revision");
        assertThat(detail.get("version").longValue()).isZero();
        assertThat(detail.get("latestRevision").intValue()).isEqualTo(1);
        assertThat(detail.get("publishedRevision").isNull()).isTrue();
        var revision = detail.get("revision");
        assertThat(revision.get("revisionNumber").intValue()).isEqualTo(1);
        assertThat(revision.get("createdBy").isString()).isTrue();
        assertThat(revision.get("createdBy").stringValue()).isEqualTo(Long.toString(TestTokens.MEMBER_ID));
        assertThat(revision.get("publishedBy").isNull()).isTrue();
        assertThat(revision.get("publishedAt").isNull()).isTrue();
        var itemNodes = revision.get("items");
        assertThat(itemNodes).extracting(node -> node.get("key").stringValue())
            .containsExactly("women", "women-new", "women-skirts", "magazine", "hidden");
        var skirts = itemNodes.get(2);
        assertThat(skirts.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
            "key", "parentKey", "type", "labels", "url", "categoryId", "displayOrder", "visible");
        assertThat(skirts.get("parentKey").stringValue()).isEqualTo("women");
        assertThat(skirts.get("categoryId").isString()).isTrue();
        assertThat(skirts.get("categoryId").stringValue()).isEqualTo("42");
        assertThat(skirts.get("url").isNull()).isTrue();
        assertThat(skirts.get("labels").get("en").stringValue()).isEqualTo("Skirts First");
        assertThat(itemNodes.get(4).get("visible").booleanValue()).isFalse();

        var read = adminGet();
        assertThat(read.getHeader("Cache-Control")).contains("no-store");
        assertThat(data(read)).isEqualTo(detail);

        var replaced = data(save(0L, "Second"));
        assertThat(replaced.get("version").longValue()).isEqualTo(1);
        assertThat(replaced.get("latestRevision").intValue()).isEqualTo(2);
        assertThat(replaced.get("publishedRevision").isNull()).isTrue();
        assertThat(data(adminGet())).isEqualTo(replaced);

        String empty = "{\"expectedVersion\":1,\"items\":[]}";
        var cleared = data(request(put(ADMIN), empty, MemberRole.ADMIN));
        assertThat(cleared.get("revision").get("items").size()).isZero();
        assertThat(revisions.count()).isEqualTo(3);
    }

    @Test
    void categoryIdAcceptsJsonStringAndAnswersAsString() throws Exception {
        var saved = request(put(ADMIN), draft(null, "Quoted").replace("\"categoryId\":42", "\"categoryId\":\"42\""), MemberRole.ADMIN);
        assertThat(saved.getStatus()).isEqualTo(200);
        var skirts = data(saved).get("revision").get("items").get(2);
        assertThat(skirts.get("key").stringValue()).isEqualTo("women-skirts");
        assertThat(skirts.get("categoryId").isString()).isTrue();
        assertThat(skirts.get("categoryId").stringValue()).isEqualTo("42");
    }

    @Test
    void invalidRequestsAndVersionConflictsKeepConsistentEnvelopeWithoutPartialWrites() throws Exception {
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("{", "STORE_NAV_100");
        invalid.put("{}", "STORE_NAV_102");
        invalid.put("{\"items\":[null]}", "STORE_NAV_102");
        invalid.put(draft(-1L, "Example"), "STORE_NAV_101");
        invalid.put(draft(null, "Example").replace("\"key\":\"women\"", "\"key\":\"Women\""), "STORE_NAV_103");
        invalid.put(draft(null, "Example").replace("\"key\":\"magazine\"", "\"key\":\"women\""), "STORE_NAV_103");
        invalid.put(draft(null, "Example").replace("\"parentKey\":\"women\"", "\"parentKey\":\"missing\""), "STORE_NAV_104");
        invalid.put(draft(null, "Example").replace("\"type\":\"EXTERNAL\"", "\"type\":\"external\""), "STORE_NAV_107");
        invalid.put(draft(null, "Example").replace("\"en\":\"Hidden Example\"", "\"en\":null"), "STORE_NAV_108");
        invalid.put(draft(null, "Example").replace("https://links.example.com/magazine", "https://unapproved.example.com/magazine"),
            "STORE_NAV_109");
        invalid.put(draft(null, "Example").replace("/collections/new", "/collections/../admin"), "STORE_NAV_109");
        invalid.put(draft(null, "Example").replace("\"categoryId\":42", "\"categoryId\":0"), "STORE_NAV_110");
        invalid.put(draft(null, "Example").replace("\"categoryId\":42", "\"categoryId\":-7"), "STORE_NAV_110");
        // long 범위를 넘는 카테고리 아이디는 역직렬화에서 실패해 일반 요청 오류(STORE_NAV_100)로 나간다.
        invalid.put(draft(null, "Example").replace("\"categoryId\":42", "\"categoryId\":9223372036854775808"), "STORE_NAV_100");
        invalid.put(draft(null, "Example").replace("\"displayOrder\":2,\"visible\":true,\"url\":null,\"categoryId\":42",
            "\"displayOrder\":1,\"visible\":true,\"url\":null,\"categoryId\":42"), "STORE_NAV_111");
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            error(request(put(ADMIN), entry.getKey(), MemberRole.ADMIN), 400, entry.getValue());
        }
        assertThat(navigations.count()).isZero();
        assertThat(revisions.count()).isZero();

        error(save(0L, "Missing"), 409, "STORE_NAV_020");
        assertThat(save(null, "Example").getStatus()).isEqualTo(200);
        error(save(null, "Duplicate"), 409, "STORE_NAV_020");
        error(save(9L, "Stale"), 409, "STORE_NAV_020");
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(data(adminGet()).get("version").longValue()).isZero();
    }

    @Test
    void auditContainsOnlyJwtActorFixedTargetActionAndOutcome() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(save(null, "PRIVATE_SENTINEL").getStatus()).isEqualTo(200);
            assertThat(save(5L, "PRIVATE_SENTINEL").getStatus()).isEqualTo(409);
            assertThat(adminGet().getStatus()).isEqualTo(200);
            String actor = " actorMemberId=" + TestTokens.MEMBER_ID + " target=store-navigation:1 result=";
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=STORE_NAVIGATION_DRAFT_SAVE" + actor + "SUCCESS", "action=STORE_NAVIGATION_DRAFT_SAVE" + actor + "FAILURE");
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE_SENTINEL", "https://", "labels", "/collections");
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
            error(save(5L, "Stale"), 409, "STORE_NAV_020");
            assertThat(revisions.count()).isEqualTo(1);
        }
    }

    @Test
    void presenterFailureAfterCommitAuditsFailureWhileCommittedStateRemainsReadable() throws Exception {
        doThrow(new IllegalStateException("PRESENTER_SENTINEL")).when(presenter).detail(any());
        try (var writer = mockStatic(AdminAuditLog.class)) {
            assertThat(save(null, "Committed").getStatus()).isEqualTo(500);
            writer.verify(() -> AdminAuditLog.write("STORE_NAVIGATION_DRAFT_SAVE", TestTokens.MEMBER_ID, "store-navigation:1", false));
            writer.verifyNoMoreInteractions();
        }
        assertThat(navigations.count()).isEqualTo(1);
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(5);
    }

    private MockHttpServletResponse request(MockHttpServletRequestBuilder request, String body, MemberRole role) throws Exception {
        request.header("Authorization", bearer(role));
        return perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MockHttpServletResponse adminGet() throws Exception {
        return request(get(ADMIN), null, MemberRole.ADMIN);
    }

    private MockHttpServletResponse save(Long version, String text) throws Exception {
        return request(put(ADMIN), draft(version, text), MemberRole.ADMIN);
    }

    /** 입력 순서를 일부러 섞는다. 응답은 트리 전위 순서(부모 다음 자식, 형제는 displayOrder 오름차순)다. */
    private String draft(Long version, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", version);
        body.put("items", List.of(
            item("magazine", null, "EXTERNAL", "매거진 " + text, "Magazine " + text, 2, true, "https://links.example.com/magazine", null),
            item("women-skirts", "women", "CATEGORY", "치마 " + text, "Skirts " + text, 2, true, null, 42L),
            item("hidden", null, "NONE", "숨김 " + text, "Hidden " + text, 3, false, null, null),
            item("women-new", "women", "INTERNAL", "신상 " + text, "New " + text, 1, true, "/collections/new", null),
            item("women", null, "NONE", "여성 " + text, "Women " + text, 1, true, null, null)));
        return JSON.writeValueAsString(body);
    }

    private Map<String, Object> item(String key, String parentKey, String type, String ko, String en, int order, boolean visible,
                                     String url, Long categoryId) {
        // 키 순서를 고정해 테스트의 문자열 치환이 한 항목에만 맞게 한다.
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("parentKey", parentKey);
        item.put("type", type);
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("ko", ko);
        labels.put("en", en);
        item.put("labels", labels);
        item.put("displayOrder", order);
        item.put("visible", visible);
        item.put("url", url);
        item.put("categoryId", categoryId);
        return item;
    }

    private JsonNode data(MockHttpServletResponse response) throws java.io.UnsupportedEncodingException {
        return JSON.readTree(response.getContentAsString()).get("dataBody");
    }

    private void error(MockHttpServletResponse response, int status, String code) throws java.io.UnsupportedEncodingException {
        assertThat(response.getStatus()).as(code).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(JSON.readTree(response.getContentAsString()).has("dataHeader")).isTrue();
        assertThat(response.getContentAsString()).as(code).contains(code);
    }
}
