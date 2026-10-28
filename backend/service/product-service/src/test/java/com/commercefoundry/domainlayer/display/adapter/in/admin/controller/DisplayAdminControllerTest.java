package com.commercefoundry.domainlayer.display.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item.DisplayElementAdminItem;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.item.DisplayProductAdminItem;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminListResponse;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminResponse;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayAdminUseCase;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayLiveStatus;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMediaType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 메인 전시 관리의 HTTP 계약 — 권한(ADMIN 만), 검증 오류 코드 봉투, 도메인 오류 봉투, 응답 모양(아이디 String · 노출 상태 metadata · 201).
 */
@WebMvcTest(DisplayAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class DisplayAdminControllerTest {

    private static final String PATH = "/api/v1/admin/displays/sections";
    private static final String VALID_BODY = """
        {"sectionType": "LOOK", "title": "  가을 룩  ", "subtitle": "   ", "body": "첫 줄\\n둘째 줄", "linkUrl": "/products", "linkLabel": "보기",
         "visible": true, "startAt": "2026-11-01T00:00:00Z", "endAt": null,
         "items": [
           {"itemType": "MEDIA", "media": {"mediaType": "VIDEO", "pcUrl": "https://cdn.example.com/v.mp4", "pcPosterUrl": "https://cdn.example.com/p.jpg"}},
           {"itemType": "PRODUCT", "productId": "4611686018427387904", "caption": "착용 상품"}
         ]}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private DisplayAdminUseCase displayAdminUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "GET, /api/v1/admin/displays/sections", "GET, /api/v1/admin/displays/sections/1", "POST, /api/v1/admin/displays/sections",
            "PUT, /api/v1/admin/displays/sections/1", "DELETE, /api/v1/admin/displays/sections/1", "PATCH, /api/v1/admin/displays/sections/order"
        })
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, bodyFor(uri), MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(displayAdminUseCase);
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"GET, /api/v1/admin/displays/sections", "POST, /api/v1/admin/displays/sections", "PATCH, /api/v1/admin/displays/sections/order"})
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, bodyFor(uri), null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(displayAdminUseCase);
        }
    }

    @Nested
    @DisplayName("조회")
    class Reads {

        @Test
        @DisplayName("전체 조회는 200 — 아이디는 String, 노출 상태 · 아이템 유형은 metadata, 아이템 아이디는 내지 않고 숨긴 상품의 visible=false 를 낸다")
        void list() {
            given(displayAdminUseCase.getSections()).willReturn(DisplaySectionAdminListResponse.builder().sections(List.of(sectionResponse())).build());

            MvcTestResult result = exchange(HttpMethod.GET, PATH, null, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].sectionId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].sectionType.code").isEqualTo("PRODUCT_PICK");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].liveStatus.code").isEqualTo("SCHEDULED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].liveStatus.name").isEqualTo("예정");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].itemType.code").isEqualTo("PRODUCT");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].itemType.name").isEqualTo("상품");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].media").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0]").asMap().doesNotContainKey("itemId");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0]").asMap().doesNotContainKeys("isLive", "displayOrder");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].product.productId").isEqualTo("700");
            assertThat(result).bodyJson().extractingPath("$.dataBody.sections[0].items[0].product.visible").isEqualTo(false);
        }

        @Test
        @DisplayName("없는 섹션은 404 · PRODUCT_046 봉투다")
        void notFound() {
            given(displayAdminUseCase.getSection(9L)).willThrow(new DisplayException(DisplayErrorCode.NOT_FOUND_DISPLAY_SECTION));

            assertFailure(exchange(HttpMethod.GET, PATH + "/9", null, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "PRODUCT_046", "존재하지 않는 전시 섹션입니다.");
        }

        @Test
        @DisplayName("경로 아이디가 0 이하면 400 · PRODUCT_160 이다")
        void nonPositiveSectionId() {
            assertFailure(exchange(HttpMethod.GET, PATH + "/0", null, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "PRODUCT_160", "sectionId 는 양수여야 합니다.");
            verifyNoInteractions(displayAdminUseCase);
        }
    }

    @Nested
    @DisplayName("생성 · 수정 · 삭제 · 순서")
    class Writes {

        @Test
        @DisplayName("생성은 201 — 문자열 productId 를 받고, 문구는 앞뒤 공백을 빼며 빈 문구는 null 로 넘긴다")
        void create() {
            given(displayAdminUseCase.createSection(any())).willReturn(sectionResponse());

            MvcTestResult result = exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataBody.sectionId").isEqualTo("4611686018427387904");
            ArgumentCaptor<DisplaySectionUpsertCommand> captor = ArgumentCaptor.forClass(DisplaySectionUpsertCommand.class);
            verify(displayAdminUseCase).createSection(captor.capture());
            DisplaySectionUpsertCommand command = captor.getValue();
            assertThat(command.sectionType()).isEqualTo(DisplaySectionType.LOOK);
            assertThat(command.title()).isEqualTo("가을 룩");
            assertThat(command.subtitle()).isNull();
            assertThat(command.body()).isEqualTo("첫 줄\n둘째 줄");
            assertThat(command.startAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
            assertThat(command.items()).extracting(DisplaySectionUpsertCommand.Item::itemType).containsExactly(DisplayItemType.MEDIA, DisplayItemType.PRODUCT);
            assertThat(command.items().get(0).media().mediaType()).isEqualTo(DisplayMediaType.VIDEO);
            assertThat(command.items().get(1).productId()).isEqualTo(4611686018427387904L);
            assertThat(command.items().get(1).caption()).isEqualTo("착용 상품");
        }

        @Test
        @DisplayName("수정은 200 이고 경로 아이디와 본문을 넘긴다")
        void update() {
            given(displayAdminUseCase.updateSection(eq(5L), any())).willReturn(sectionResponse());

            assertThat(exchange(HttpMethod.PUT, PATH + "/5", VALID_BODY, MemberRole.ADMIN)).hasStatusOk();
            verify(displayAdminUseCase).updateSection(eq(5L), any());
        }

        @Test
        @DisplayName("삭제는 200 · dataBody null 이다")
        void delete() {
            MvcTestResult result = exchange(HttpMethod.DELETE, PATH + "/5", null, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verify(displayAdminUseCase).deleteSection(5L);
        }

        @Test
        @DisplayName("순서 변경은 문자열 아이디를 받아 순서대로 넘긴다")
        void reorder() {
            given(displayAdminUseCase.reorderSections(anyList())).willReturn(DisplaySectionAdminListResponse.builder().sections(List.of()).build());

            assertThat(exchange(HttpMethod.PATCH, PATH + "/order", "{\"sectionIds\": [\"3\", \"1\", \"2\"]}", MemberRole.ADMIN)).hasStatusOk();
            verify(displayAdminUseCase).reorderSections(List.of(3L, 1L, 2L));
        }

        @Test
        @DisplayName("도메인 오류는 그 코드 · 상태의 봉투다 (예: 섹션 수 상한 409 · PRODUCT_056, 미디어 저장소 미설정 503 · PRODUCT_055)")
        void domainErrors() {
            given(displayAdminUseCase.createSection(any())).willThrow(new DisplayException(DisplayErrorCode.DISPLAY_SECTION_LIMIT_EXCEEDED, 50));
            assertFailure(exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN), HttpStatus.CONFLICT, "PRODUCT_056", "전시 섹션은 최대 50개까지 만들 수 있습니다.");

            given(displayAdminUseCase.updateSection(anyLong(), any())).willThrow(new DisplayException(DisplayErrorCode.DISPLAY_MEDIA_STORAGE_NOT_CONFIGURED));
            assertFailure(exchange(HttpMethod.PUT, PATH + "/1", VALID_BODY, MemberRole.ADMIN), HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_055",
                "미디어 저장소 주소가 설정되지 않아 이미지 · 영상을 등록할 수 없습니다.");
        }
    }

    @Nested
    @DisplayName("본문 검증 — 400 봉투, 유스케이스에 닿지 않는다")
    class Validation {

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', textBlock = """
            {"title": "a", "visible": true, "items": []}                                                             | PRODUCT_140
            {"sectionType": "HERO", "visible": true}                                                                 | PRODUCT_147
            {"sectionType": "HERO", "items": []}                                                                     | PRODUCT_146
            {"sectionType": "HERO", "visible": true, "items": [null]}                                                | PRODUCT_149
            {"sectionType": "HERO", "visible": true, "items": [{"media": null}]}                                     | PRODUCT_150
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "MEDIA"}]}                               | PRODUCT_157
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "PRODUCT"}]}                             | PRODUCT_158
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "PRODUCT", "productId": 0}]}             | PRODUCT_155
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "MEDIA", "media": {"mediaType": "VIDEO", "pcUrl": "https://c/v.mp4"}}]} | PRODUCT_159
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "MEDIA", "media": {"mediaType": "IMAGE", "pcUrl": " "}}]}               | PRODUCT_152
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "MEDIA", "media": {"mediaType": "IMAGE", "pcUrl": "\\u3000"}}]}          | PRODUCT_152
            {"sectionType": "HERO", "visible": true, "items": [{"itemType": "MEDIA", "media": {"pcUrl": "https://c/a.jpg"}}]}                       | PRODUCT_151
            {"sectionType": "HERO", "visible": true, "title": "a\\u0000b", "items": []}                              | PRODUCT_164
            {"sectionType": "HERO", "visible": true, "body": "a\\u0007b", "items": []}                               | PRODUCT_164
            {"sectionType": "NOPE", "visible": true, "items": []}                                                    | PRODUCT_100
            """)
        @DisplayName("필드별 코드로 거절한다")
        void rejects(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.POST, PATH, body, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(displayAdminUseCase);
        }

        @Test
        @DisplayName("노출 시각이 0001~9999년 밖이면 PRODUCT_165 다")
        void periodOutOfRange() {
            assertCode("{\"sectionType\": \"STORY\", \"visible\": true, \"startAt\": \"+10000-01-01T00:00:00Z\", \"items\": []}", "PRODUCT_165");
            assertCode("{\"sectionType\": \"STORY\", \"visible\": true, \"endAt\": \"-0001-01-01T00:00:00Z\", \"items\": []}", "PRODUCT_165");
            verifyNoInteractions(displayAdminUseCase);
        }

        @Test
        @DisplayName("길이 상한 — 제목 201자는 PRODUCT_141, 본문 2001자는 PRODUCT_143, 아이템 31개는 PRODUCT_148 이다")
        void sizeLimits() {
            assertCode("{\"sectionType\": \"HERO\", \"visible\": true, \"title\": \"" + "가".repeat(201) + "\", \"items\": []}", "PRODUCT_141");
            assertCode("{\"sectionType\": \"HERO\", \"visible\": true, \"body\": \"" + "가".repeat(2001) + "\", \"items\": []}", "PRODUCT_143");
            String item = "{\"itemType\": \"PRODUCT\", \"productId\": 1}";
            assertCode("{\"sectionType\": \"HERO\", \"visible\": true, \"items\": [" + String.join(",", java.util.Collections.nCopies(31, item)) + "]}", "PRODUCT_148");
        }

        @Test
        @DisplayName("본문은 개행 · 탭을 받는다")
        void bodyAllowsLineBreaks() {
            given(displayAdminUseCase.createSection(any())).willReturn(sectionResponse());

            assertThat(exchange(HttpMethod.POST, PATH, "{\"sectionType\": \"STORY\", \"visible\": true, \"body\": \"a\\r\\n\\tb\", \"items\": []}", MemberRole.ADMIN))
                .hasStatus(HttpStatus.CREATED);
        }

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', textBlock = """
            {}                          | PRODUCT_161
            {"sectionIds": [null]}      | PRODUCT_163
            {"sectionIds": ["-1"]}      | PRODUCT_163
            """)
        @DisplayName("순서 변경 본문 검증")
        void reorderValidation(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.PATCH, PATH + "/order", body, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(displayAdminUseCase);
        }

        private void assertCode(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.POST, PATH, body, MemberRole.ADMIN);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        }
    }

    private static DisplaySectionAdminResponse sectionResponse() {
        return DisplaySectionAdminResponse.builder()
            .sectionId("4611686018427387904")
            .sectionType(DisplaySectionType.PRODUCT_PICK.toMetadata())
            .visible(true)
            .liveStatus(DisplayLiveStatus.SCHEDULED.toMetadata())
            .items(List.of(DisplayElementAdminItem.builder()
                .itemType(DisplayItemType.PRODUCT.toMetadata())
                .product(DisplayProductAdminItem.builder().productId("700").name("숨긴 상품").visible(false).build())
                .build()))
            .build();
    }

    private static String bodyFor(String uri) {
        return uri.endsWith("/order") ? "{\"sectionIds\": [\"1\"]}" : VALID_BODY;
    }

    private MvcTestResult exchange(HttpMethod method, String uri, String body, MemberRole role) {
        var request = mvc.method(method).uri(uri);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        if (body != null && (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code, String message) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }
}
