package com.commercefoundry.domainlayer.storenavigation;

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
    "spring.datasource.url=jdbc:h2:mem:store-navigation-flow-599;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "commerce.store.navigation.allowed-external-hosts=links.example.com"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreNavigationFlowTest extends ResourceServerSmokeTestSupport {
    private static final String ADMIN = "/api/v1/admin/store/navigation";
    private static final String PUBLICATION = ADMIN + "/publication";
    private static final String PUBLIC = "/api/v1/store/navigation";
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
        assertThat(perform(post(PUBLICATION).contentType(MediaType.APPLICATION_JSON).content(publication(0, 1))).getStatus()).isEqualTo(401);
        for (MemberRole role : List.of(MemberRole.CUSTOMER, MemberRole.MANAGER, MemberRole.STAFF)) {
            assertThat(request(get(ADMIN), null, role).getStatus()).isEqualTo(403);
            assertThat(request(put(ADMIN), draft(null, "Example"), role).getStatus()).isEqualTo(403);
            assertThat(request(post(PUBLICATION), publication(0, 1), role).getStatus()).isEqualTo(403);
        }
        assertThat(navigations.count()).isZero();
        assertThat(request(put(ADMIN), draft(null, "Example"), MemberRole.OWNER).getStatus()).isEqualTo(200);
        for (MemberRole role : List.of(MemberRole.ADMIN, MemberRole.OWNER)) {
            assertThat(request(get(ADMIN), null, role).getStatus()).isEqualTo(200);
        }
        assertThat(request(post(PUBLICATION), publication(0, 1), MemberRole.OWNER).getStatus()).isEqualTo(200);
        assertThat(save(1L, "Admin").getStatus()).isEqualTo(200);
        assertThat(publish(2, 2).getStatus()).isEqualTo(200);
        // 공개 조회는 토큰이 없어도 되고, 고객 토큰이어도 같은 결과다.
        assertThat(perform(get(PUBLIC)).getStatus()).isEqualTo(200);
        assertThat(request(get(PUBLIC), null, MemberRole.CUSTOMER).getStatus()).isEqualTo(200);
    }

    @Test
    void publishedTreeIsLocalizedOrderedHidesHiddenSubtreeAndNeverLeaksDraftsOrKeys() throws Exception {
        error(perform(get(PUBLIC)), 404, "STORE_NAV_001");
        assertThat(save(null, "First").getStatus()).isEqualTo(200);
        // 저장만 한 초안은 공개되지 않는다.
        error(perform(get(PUBLIC)), 404, "STORE_NAV_001");

        var published = publish(0, 1);
        assertThat(published.getStatus()).isEqualTo(200);
        assertThat(published.getHeader("Cache-Control")).contains("no-store");
        var detail = data(published);
        assertThat(detail.get("version").longValue()).isEqualTo(1);
        assertThat(detail.get("publishedRevision").intValue()).isEqualTo(1);
        assertThat(detail.get("revision").get("publishedBy").stringValue()).isEqualTo(Long.toString(TestTokens.MEMBER_ID));
        assertThat(detail.get("revision").get("publishedAt").isNull()).isFalse();

        var publicResponse = perform(get(PUBLIC));
        assertThat(publicResponse.getHeader("Cache-Control")).contains("no-store");
        var ko = data(publicResponse);
        assertThat(ko.properties()).extracting(Map.Entry::getKey).containsExactly("items");
        var top = ko.get("items");
        assertThat(top).extracting(node -> node.get("label").stringValue()).containsExactly("여성 First", "매거진 First");
        var women = top.get(0);
        assertThat(women.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
            "label", "description", "type", "url", "categoryId", "primary", "shortcut", "children");
        assertThat(women.get("description").stringValue()).isEqualTo("전체 상품 / 카테고리 First");
        assertThat(women.get("primary").booleanValue()).isTrue();
        assertThat(women.get("shortcut").booleanValue()).isFalse();
        assertThat(women.get("children")).extracting(node -> node.get("label").stringValue()).containsExactly("신상 First", "치마 First");
        var skirts = women.get("children").get(1);
        assertThat(skirts.get("type").stringValue()).isEqualTo("CATEGORY");
        assertThat(skirts.get("categoryId").isString()).isTrue();
        assertThat(skirts.get("categoryId").stringValue()).isEqualTo("42");
        assertThat(skirts.get("url").isNull()).isTrue();
        assertThat(skirts.get("description").isNull()).isTrue();
        assertThat(skirts.get("children").size()).isZero();
        var magazine = top.get(1);
        assertThat(magazine.get("url").stringValue()).isEqualTo("https://links.example.com/magazine");
        assertThat(magazine.get("shortcut").booleanValue()).isTrue();
        assertThat(magazine.get("primary").booleanValue()).isFalse();
        // 숨김 부모 아래의 노출 항목, 편집용 key, 버전·작성자는 공개 응답에 없다.
        assertThat(ko.toString()).doesNotContain("숨김", "Hidden", "\"key\"", "parentKey", "women-new", "version", "createdBy", "displayOrder");

        var en = data(perform(get(PUBLIC).param("locale", "en")));
        assertThat(en.get("items")).extracting(node -> node.get("label").stringValue()).containsExactly("Women First", "Magazine First");
        assertThat(en.get("items").get(0).get("description").stringValue()).isEqualTo("All products / Categories First");
        assertThat(en.get("items").get(0).get("children").get(0).get("label").stringValue()).isEqualTo("New First");
        assertThat(en.toString()).doesNotContain("여성", "전체 상품");

        // 새 초안 저장은 게시본을 바꾸지 않는다. 현재 게시본의 반복 게시는 오래된 버전이어도 변경 없이 성공한다.
        assertThat(save(1L, "Private").getStatus()).isEqualTo(200);
        assertThat(data(perform(get(PUBLIC)))).isEqualTo(ko);
        var latest = data(adminGet());
        assertThat(latest.get("version").longValue()).isEqualTo(2);
        assertThat(latest.get("revision").get("publishedAt").isNull()).isTrue();
        assertThat(data(publish(0, 1))).isEqualTo(latest);
        assertThat(data(adminGet())).isEqualTo(latest);
        error(publish(1, 2), 409, "STORE_NAV_020");
        error(publish(2, 3), 409, "STORE_NAV_020");
        var second = data(publish(2, 2));
        assertThat(second.get("version").longValue()).isEqualTo(3);
        assertThat(second.get("publishedRevision").intValue()).isEqualTo(2);
        assertThat(data(perform(get(PUBLIC))).get("items").get(0).get("label").stringValue()).isEqualTo("여성 Private");
        error(publish(3, 1), 409, "STORE_NAV_020");
    }

    @Test
    void emptyPublishedMenuIsOkAndInvalidLocaleIsRejected() throws Exception {
        assertThat(request(put(ADMIN), "{\"expectedVersion\":null,\"items\":[]}", MemberRole.ADMIN).getStatus()).isEqualTo(200);
        assertThat(publish(0, 1).getStatus()).isEqualTo(200);
        assertThat(data(perform(get(PUBLIC).param("locale", "en"))).get("items").size()).isZero();
        for (String locale : List.of("KO", "en-US", "fr", "")) {
            error(perform(get(PUBLIC).param("locale", locale)), 400, "STORE_NAV_100");
        }
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
            .containsExactly("women", "women-new", "women-skirts", "magazine", "hidden", "hidden-child");
        var skirts = itemNodes.get(2);
        assertThat(skirts.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
            "key", "parentKey", "type", "labels", "descriptions", "url", "categoryId", "displayOrder", "visible", "primary", "shortcut");
        assertThat(skirts.get("parentKey").stringValue()).isEqualTo("women");
        assertThat(skirts.get("categoryId").isString()).isTrue();
        assertThat(skirts.get("categoryId").stringValue()).isEqualTo("42");
        assertThat(skirts.get("url").isNull()).isTrue();
        assertThat(skirts.get("labels").get("en").stringValue()).isEqualTo("Skirts First");
        // 생략한 설명·노출 위치는 null·false 로 저장된다.
        assertThat(skirts.get("descriptions").isNull()).isTrue();
        assertThat(skirts.get("primary").booleanValue()).isFalse();
        assertThat(skirts.get("shortcut").booleanValue()).isFalse();
        var women = itemNodes.get(0);
        assertThat(women.get("descriptions").get("ko").stringValue()).isEqualTo("전체 상품 / 카테고리 First");
        assertThat(women.get("descriptions").get("en").stringValue()).isEqualTo("All products / Categories First");
        assertThat(women.get("primary").booleanValue()).isTrue();
        assertThat(itemNodes.get(3).get("shortcut").booleanValue()).isTrue();
        assertThat(itemNodes.get(4).get("visible").booleanValue()).isFalse();
        assertThat(itemNodes.get(4).get("primary").booleanValue()).isTrue();

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
        invalid.put(draft(null, "Example").replace("\"en\":\"All products / Categories Example\"", "\"en\":null"), "STORE_NAV_114");
        invalid.put(draft(null, "Example").replace("\"ko\":\"전체 상품 / 카테고리 Example\"", "\"ko\":\"" + "가".repeat(81) + "\""), "STORE_NAV_114");
        // 하위 항목의 PC 1차 메뉴·모바일 바로가기는 숨김 항목이어도 거부한다.
        invalid.put(draft(null, "Example").replace("\"url\":\"/collections/new\",\"categoryId\":null",
            "\"url\":\"/collections/new\",\"categoryId\":null,\"shortcut\":true"), "STORE_NAV_115");
        invalid.put(draft(null, "Example").replace("\"url\":\"/sale/kids\",\"categoryId\":null",
            "\"url\":\"/sale/kids\",\"categoryId\":null,\"primary\":true"), "STORE_NAV_115");
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            error(request(put(ADMIN), entry.getKey(), MemberRole.ADMIN), 400, entry.getValue());
        }
        assertThat(navigations.count()).isZero();
        assertThat(revisions.count()).isZero();
        error(publish(0, 1), 404, "STORE_NAV_001");

        error(save(0L, "Missing"), 409, "STORE_NAV_020");
        assertThat(save(null, "Example").getStatus()).isEqualTo(200);
        error(save(null, "Duplicate"), 409, "STORE_NAV_020");
        error(save(9L, "Stale"), 409, "STORE_NAV_020");
        Map<String, String> invalidPublications = new LinkedHashMap<>();
        invalidPublications.put("{", "STORE_NAV_100");
        // 버전·리비전 번호가 둘 다 빠지면 오류가 둘이라 접두어만 본다.
        invalidPublications.put("{}", "STORE_NAV_1");
        invalidPublications.put("{\"expectedVersion\":0}", "STORE_NAV_113");
        invalidPublications.put("{\"revisionNumber\":1}", "STORE_NAV_101");
        invalidPublications.put("{\"expectedVersion\":-1,\"revisionNumber\":1}", "STORE_NAV_101");
        invalidPublications.put("{\"expectedVersion\":0,\"revisionNumber\":0}", "STORE_NAV_113");
        for (Map.Entry<String, String> entry : invalidPublications.entrySet()) {
            error(request(post(PUBLICATION), entry.getKey(), MemberRole.ADMIN), 400, entry.getValue());
        }
        error(publish(0, 2), 409, "STORE_NAV_020");
        error(publish(1, 1), 409, "STORE_NAV_020");
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(data(adminGet()).get("version").longValue()).isZero();
        assertThat(data(adminGet()).get("publishedRevision").isNull()).isTrue();
        error(perform(get(PUBLIC)), 404, "STORE_NAV_001");
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
            assertThat(publish(0, 1).getStatus()).isEqualTo(200);
            assertThat(publish(0, 2).getStatus()).isEqualTo(409);
            assertThat(adminGet().getStatus()).isEqualTo(200);
            assertThat(perform(get(PUBLIC)).getStatus()).isEqualTo(200);
            String actor = " actorMemberId=" + TestTokens.MEMBER_ID + " target=store-navigation:1 result=";
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=STORE_NAVIGATION_DRAFT_SAVE" + actor + "SUCCESS", "action=STORE_NAVIGATION_DRAFT_SAVE" + actor + "FAILURE",
                "action=STORE_NAVIGATION_PUBLISH" + actor + "SUCCESS", "action=STORE_NAVIGATION_PUBLISH" + actor + "FAILURE");
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void presenterFailureAfterCommitAuditsFailureWhileCommittedStateRemainsReadable(boolean publishing) throws Exception {
        if (publishing) {
            assertThat(save(null, "Committed").getStatus()).isEqualTo(200);
        }
        doThrow(new IllegalStateException("PRESENTER_SENTINEL")).when(presenter).detail(any());
        try (var writer = mockStatic(AdminAuditLog.class)) {
            assertThat((publishing ? publish(0, 1) : save(null, "Committed")).getStatus()).isEqualTo(500);
            String action = publishing ? "STORE_NAVIGATION_PUBLISH" : "STORE_NAVIGATION_DRAFT_SAVE";
            writer.verify(() -> AdminAuditLog.write(action, TestTokens.MEMBER_ID, "store-navigation:1", false));
            writer.verifyNoMoreInteractions();
        }
        assertThat(navigations.count()).isEqualTo(1);
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(6);
        // 커밋은 이미 끝났다. 게시였다면 공개 조회에 바로 보인다.
        assertThat(perform(get(PUBLIC)).getStatus()).isEqualTo(publishing ? 200 : 404);
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

    private MockHttpServletResponse publish(long version, int revision) throws Exception {
        return request(post(PUBLICATION), publication(version, revision), MemberRole.ADMIN);
    }

    private String publication(long version, int revision) {
        return JSON.writeValueAsString(Map.of("expectedVersion", version, "revisionNumber", revision));
    }

    /**
     * 입력 순서를 일부러 섞는다. 응답은 트리 전위 순서(부모 다음 자식, 형제는 displayOrder 오름차순)다.
     * women 은 설명·PC 1차 메뉴, magazine 은 모바일 바로가기, hidden 은 숨김 최상위(그 아래 노출 항목 hidden-child)다.
     */
    private String draft(Long version, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", version);
        body.put("items", List.of(
            item("magazine", null, "EXTERNAL", "매거진 " + text, "Magazine " + text, 2, true, "https://links.example.com/magazine", null,
                Map.of("shortcut", true)),
            item("women-skirts", "women", "CATEGORY", "치마 " + text, "Skirts " + text, 2, true, null, 42L, Map.of()),
            item("hidden-child", "hidden", "INTERNAL", "숨김 하위 " + text, "Hidden child " + text, 1, true, "/sale/kids", null, Map.of()),
            item("hidden", null, "NONE", "숨김 " + text, "Hidden " + text, 3, false, null, null, Map.of("primary", true)),
            item("women-new", "women", "INTERNAL", "신상 " + text, "New " + text, 1, true, "/collections/new", null, Map.of()),
            item("women", null, "NONE", "여성 " + text, "Women " + text, 1, true, null, null, Map.of("primary", true))));
        return JSON.writeValueAsString(body);
    }

    private Map<String, Object> item(String key, String parentKey, String type, String ko, String en, int order, boolean visible,
                                     String url, Long categoryId, Map<String, Object> flags) {
        // 키 순서를 고정해 테스트의 문자열 치환이 한 항목에만 맞게 한다. 설명은 women 에만, 생략한 플래그는 보내지 않는다.
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("parentKey", parentKey);
        item.put("type", type);
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("ko", ko);
        labels.put("en", en);
        item.put("labels", labels);
        if ("women".equals(key)) {
            Map<String, Object> descriptions = new LinkedHashMap<>();
            descriptions.put("ko", "전체 상품 / 카테고리 " + en.substring("Women ".length()));
            descriptions.put("en", "All products / Categories " + en.substring("Women ".length()));
            item.put("descriptions", descriptions);
        }
        item.put("displayOrder", order);
        item.put("visible", visible);
        item.put("url", url);
        item.put("categoryId", categoryId);
        item.putAll(flags);
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
