package com.commercefoundry.domainlayer.look.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item.LookImageAdminItem;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item.LookProductAdminItem;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminListResponse;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminResponse;
import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.port.in.LookAdminUseCase;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.Collections;
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
 * 어드민 룩 관리의 HTTP 계약 — 권한(ADMIN 만), 검증 오류 코드 봉투(LOOK_1xx · 폴백 PRODUCT_100), 도메인 오류 봉투, 응답 모양(아이디 String · 순서 번호 없음 · 201).
 */
@WebMvcTest(LookAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class LookAdminControllerTest {

    private static final String PATH = "/api/v1/admin/looks";
    private static final String VALID_BODY = """
        {"title": "  가을 룩  ", "description": "   ", "visible": true,
         "image": {"url": " https://cdn.example.com/media/looks/a.jpg ", "width": 1200, "height": 1600, "altText": "  "},
         "productIds": ["4611686018427387904", 7]}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LookAdminUseCase lookAdminUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "GET, /api/v1/admin/looks", "GET, /api/v1/admin/looks/1", "POST, /api/v1/admin/looks",
            "PUT, /api/v1/admin/looks/1", "DELETE, /api/v1/admin/looks/1", "PATCH, /api/v1/admin/looks/order"
        })
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, bodyFor(uri), MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(lookAdminUseCase);
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"GET, /api/v1/admin/looks", "POST, /api/v1/admin/looks", "PATCH, /api/v1/admin/looks/order"})
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, bodyFor(uri), null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(lookAdminUseCase);
        }
    }

    @Nested
    @DisplayName("조회")
    class Reads {

        @Test
        @DisplayName("전체 조회는 200 — 아이디는 String, 판매 상태는 metadata, 순서 번호는 내지 않고 숨긴 상품의 visible=false · 없는 상품의 name null 을 낸다")
        void list() {
            given(lookAdminUseCase.getLooks()).willReturn(LookAdminListResponse.builder().looks(List.of(lookResponse())).build());

            MvcTestResult result = exchange(HttpMethod.GET, PATH, null, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].lookId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0]").asMap().doesNotContainKey("displayOrder");
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].image.width").isEqualTo(1200);
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].products[0].productId").isEqualTo("700");
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].products[0].visible").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].products[0].saleStatus.code").isEqualTo("STOP_SELLING");
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].products[0].saleStatus.name").isEqualTo("판매 종료");
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].products[1].name").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.looks[0].products[1].saleStatus").isNull();
        }

        @Test
        @DisplayName("없는 룩은 404 · LOOK_001 봉투다")
        void notFound() {
            given(lookAdminUseCase.getLook(9L)).willThrow(new LookException(LookErrorCode.NOT_FOUND_LOOK));

            assertFailure(exchange(HttpMethod.GET, PATH + "/9", null, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "LOOK_001", "존재하지 않는 룩입니다.");
        }

        @Test
        @DisplayName("경로 아이디가 0 이하면 400 · LOOK_118 이다")
        void nonPositiveLookId() {
            assertFailure(exchange(HttpMethod.GET, PATH + "/0", null, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "LOOK_118", "lookId 는 양수여야 합니다.");
            verifyNoInteractions(lookAdminUseCase);
        }

        @Test
        @DisplayName("경로 아이디가 숫자가 아니면 400 · PRODUCT_198 이다")
        void nonNumericLookId() {
            MvcTestResult result = exchange(HttpMethod.GET, PATH + "/abc", null, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_198");
        }
    }

    @Nested
    @DisplayName("생성 · 수정 · 삭제 · 순서")
    class Writes {

        @Test
        @DisplayName("생성은 201 — 문자열 productId 를 받고 배열 순서를 지키며, 문구는 앞뒤 공백을 빼고 빈 문구는 null 로 넘긴다")
        void create() {
            given(lookAdminUseCase.create(any())).willReturn(lookResponse());

            MvcTestResult result = exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataBody.lookId").isEqualTo("4611686018427387904");
            ArgumentCaptor<LookUpsertCommand> captor = ArgumentCaptor.forClass(LookUpsertCommand.class);
            verify(lookAdminUseCase).create(captor.capture());
            LookUpsertCommand command = captor.getValue();
            assertThat(command.title()).isEqualTo("가을 룩");
            assertThat(command.description()).isNull();
            assertThat(command.visible()).isTrue();
            assertThat(command.image().url()).isEqualTo("https://cdn.example.com/media/looks/a.jpg");
            assertThat(command.image().width()).isEqualTo(1200);
            assertThat(command.image().altText()).isNull();
            assertThat(command.productIds()).containsExactly(4611686018427387904L, 7L);
        }

        @Test
        @DisplayName("수정은 200 이고 경로 아이디와 본문을 넘긴다")
        void update() {
            given(lookAdminUseCase.update(eq(5L), any())).willReturn(lookResponse());

            assertThat(exchange(HttpMethod.PUT, PATH + "/5", VALID_BODY, MemberRole.ADMIN)).hasStatusOk();
            verify(lookAdminUseCase).update(eq(5L), any());
        }

        @Test
        @DisplayName("삭제는 200 · dataBody null 이다")
        void delete() {
            MvcTestResult result = exchange(HttpMethod.DELETE, PATH + "/5", null, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verify(lookAdminUseCase).delete(5L);
        }

        @Test
        @DisplayName("순서 변경은 문자열 아이디를 받아 순서대로 넘기고, 집합이 다르면 400 · LOOK_007 봉투다")
        void reorder() {
            given(lookAdminUseCase.reorder(anyList())).willReturn(LookAdminListResponse.builder().looks(List.of()).build());

            assertThat(exchange(HttpMethod.PATCH, PATH + "/order", "{\"lookIds\": [\"3\", \"1\", \"2\"]}", MemberRole.ADMIN)).hasStatusOk();
            verify(lookAdminUseCase).reorder(List.of(3L, 1L, 2L));

            given(lookAdminUseCase.reorder(anyList())).willThrow(new LookException(LookErrorCode.LOOK_ORDER_MISMATCH));
            assertFailure(exchange(HttpMethod.PATCH, PATH + "/order", "{\"lookIds\": [\"1\"]}", MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "LOOK_007",
                "순서 변경에는 현재 룩 아이디를 빠짐없이 한 번씩 보내야 합니다.");
        }

        @Test
        @DisplayName("도메인 오류는 그 코드 · 상태의 봉투다 (상한 409 · 없는 상품 400 · 저장소 미설정 503 · 잠금 409)")
        void domainErrors() {
            given(lookAdminUseCase.create(any())).willThrow(new LookException(LookErrorCode.LOOK_LIMIT_EXCEEDED, 100));
            assertFailure(exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN), HttpStatus.CONFLICT, "LOOK_002", "룩은 최대 100개까지 만들 수 있습니다.");

            willThrow(new LookException(LookErrorCode.LOOK_PRODUCT_NOT_FOUND, "7, 8")).given(lookAdminUseCase).create(any());
            assertFailure(exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "LOOK_004", "존재하지 않는 상품이 있습니다: 7, 8");

            given(lookAdminUseCase.update(eq(1L), any())).willThrow(new LookException(LookErrorCode.LOOK_MEDIA_STORAGE_NOT_CONFIGURED));
            assertFailure(exchange(HttpMethod.PUT, PATH + "/1", VALID_BODY, MemberRole.ADMIN), HttpStatus.SERVICE_UNAVAILABLE, "LOOK_006",
                "미디어 저장소 주소가 설정되지 않아 룩 이미지를 등록할 수 없습니다.");

            given(lookAdminUseCase.update(eq(2L), any())).willThrow(new LookException(LookErrorCode.LOOK_BUSY));
            assertFailure(exchange(HttpMethod.PUT, PATH + "/2", VALID_BODY, MemberRole.ADMIN), HttpStatus.CONFLICT, "LOOK_008", "다른 룩 변경을 처리 중입니다. 다시 시도해 주세요.");
        }
    }

    @Nested
    @DisplayName("본문 검증 — 400 봉투, 유스케이스에 닿지 않는다")
    class Validation {

        private static final String IMAGE = "\"image\": {\"url\": \"https://cdn.example.com/media/a.jpg\"}";

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', textBlock = """
            {"visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": [1]}                               | LOOK_101
            {"title": "  ", "visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": [1]}                | LOOK_101
            {"title": "\\u3000", "visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": [1]}           | LOOK_101
            {"title": "a", "visible": true, "image": {"url": "\\u3000"}, "productIds": [1]}                          | LOOK_107
            {"title": "a\\u0000b", "visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": [1]}         | LOOK_103
            {"title": "a", "description": "a\\u0007b", "visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": [1]} | LOOK_105
            {"title": "a", "visible": true, "productIds": [1]}                                                       | LOOK_106
            {"title": "a", "visible": true, "image": {"url": " "}, "productIds": [1]}                                | LOOK_107
            {"title": "a", "visible": true, "image": {"url": "https://c/a.jpg", "width": 0}, "productIds": [1]}      | LOOK_109
            {"title": "a", "visible": true, "image": {"url": "https://c/a.jpg", "height": -1}, "productIds": [1]}    | LOOK_110
            {"title": "a", "visible": true, "image": {"url": "https://c/a.jpg", "altText": "a\\u0001"}, "productIds": [1]} | LOOK_112
            {"title": "a", "image": {"url": "https://c/a.jpg"}, "productIds": [1]}                                   | LOOK_113
            {"title": "a", "visible": true, "image": {"url": "https://c/a.jpg"}}                                     | LOOK_114
            {"title": "a", "visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": []}                   | LOOK_115
            {"title": "a", "visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": [null]}               | LOOK_116
            {"title": "a", "visible": true, "image": {"url": "https://c/a.jpg"}, "productIds": ["0"]}                | LOOK_117
            {"title": "a", "visible": "maybe", "image": {"url": "https://c/a.jpg"}, "productIds": [1]}               | PRODUCT_100
            """)
        @DisplayName("필드별 코드로 거절한다")
        void rejects(String body, String code) {
            assertCode(body, code);
            verifyNoInteractions(lookAdminUseCase);
        }

        @Test
        @DisplayName("길이 상한 — 제목 101자 LOOK_102, 설명 1001자 LOOK_104, 주소 501자 LOOK_108, 대체 텍스트 201자 LOOK_111, 상품 21개 LOOK_115")
        void sizeLimits() {
            assertCode("{\"title\": \"" + "가".repeat(101) + "\", \"visible\": true, " + IMAGE + ", \"productIds\": [1]}", "LOOK_102");
            assertCode("{\"title\": \"a\", \"description\": \"" + "가".repeat(1001) + "\", \"visible\": true, " + IMAGE + ", \"productIds\": [1]}", "LOOK_104");
            assertCode("{\"title\": \"a\", \"visible\": true, \"image\": {\"url\": \"https://c/" + "a".repeat(500) + "\"}, \"productIds\": [1]}", "LOOK_108");
            String altText = "\"altText\": \"" + "가".repeat(201) + "\"";
            assertCode("{\"title\": \"a\", \"visible\": true, \"image\": {\"url\": \"https://c/a.jpg\", " + altText + "}, \"productIds\": [1]}", "LOOK_111");
            assertCode("{\"title\": \"a\", \"visible\": true, " + IMAGE + ", \"productIds\": [" + String.join(",", Collections.nCopies(21, "1")) + "]}", "LOOK_115");
            verifyNoInteractions(lookAdminUseCase);
        }

        @Test
        @DisplayName("선택 칸(설명 · 대체 텍스트)은 전각 공백만 있어도 판정과 같은 strip 기준으로 null 이 되어 201 이다")
        void optionalTextBlankByStripBecomesNull() {
            given(lookAdminUseCase.create(any())).willReturn(lookResponse());
            String body = "{\"title\": \"a\", \"description\": \"\\u3000\", \"visible\": true,"
                + " \"image\": {\"url\": \"https://c/a.jpg\", \"altText\": \"\\u3000\"}, \"productIds\": [1]}";

            assertThat(exchange(HttpMethod.POST, PATH, body, MemberRole.ADMIN)).hasStatus(HttpStatus.CREATED);
            ArgumentCaptor<LookUpsertCommand> captor = ArgumentCaptor.forClass(LookUpsertCommand.class);
            verify(lookAdminUseCase).create(captor.capture());
            assertThat(captor.getValue().description()).isNull();
            assertThat(captor.getValue().image().altText()).isNull();
        }

        @Test
        @DisplayName("설명은 개행 · 탭을 받는다")
        void descriptionAllowsLineBreaks() {
            given(lookAdminUseCase.create(any())).willReturn(lookResponse());

            String body = "{\"title\": \"a\", \"description\": \"a\\r\\n\\tb\",\"visible\": true, " + IMAGE + ", \"productIds\": [1]}";
            assertThat(exchange(HttpMethod.POST, PATH, body, MemberRole.ADMIN))
                .hasStatus(HttpStatus.CREATED);
        }

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', textBlock = """
            {}                       | LOOK_119
            {"lookIds": [null]}      | LOOK_121
            {"lookIds": ["-1"]}      | LOOK_122
            """)
        @DisplayName("순서 변경 본문 검증")
        void reorderValidation(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.PATCH, PATH + "/order", body, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(lookAdminUseCase);
        }

        @Test
        @DisplayName("순서 변경 아이디가 100개를 넘으면 LOOK_120 이다")
        void reorderSizeLimit() {
            MvcTestResult result = exchange(HttpMethod.PATCH, PATH + "/order", "{\"lookIds\": [" + String.join(",", Collections.nCopies(101, "1")) + "]}", MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("LOOK_120");
        }

        private void assertCode(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.POST, PATH, body, MemberRole.ADMIN);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        }
    }

    private static LookAdminResponse lookResponse() {
        return LookAdminResponse.builder()
            .lookId("4611686018427387904")
            .title("가을 룩")
            .image(LookImageAdminItem.builder().url("https://cdn.example.com/media/looks/a.jpg").width(1200).height(1600).build())
            .visible(true)
            .products(List.of(
                LookProductAdminItem.builder().productId("700").name("숨긴 상품").visible(false).saleStatus(SaleStatus.STOP_SELLING.toMetadata()).build(),
                LookProductAdminItem.builder().productId("701").visible(false).build()
            ))
            .build();
    }

    private static String bodyFor(String uri) {
        return uri.endsWith("/order") ? "{\"lookIds\": [\"1\"]}" : VALID_BODY;
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
