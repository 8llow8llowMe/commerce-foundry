package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.CategoryAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminTreeResponse;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.CategoryAdminUseCase;
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
 * 어드민 카테고리 관리의 HTTP 계약 — 권한(ADMIN 만), 검증 오류 코드, 도메인 오류 봉투, 응답 모양(아이디 String · 숨김 포함 트리 · 201).
 *
 * <p>슬라이스라 {@code *BeansConfig} 가 빠지므로 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(CategoryAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class CategoryAdminControllerTest {

    private static final String PATH = "/api/v1/admin/categories";
    private static final String ITEM_PATH = "/api/v1/admin/categories/101";
    private static final String VALID_BODY = """
        {"parentId": "1", "name": "  저고리  ", "displayOrder": 2, "visible": false}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CategoryAdminUseCase categoryAdminUseCase;

    @Nested
    @DisplayName("트리 조회 GET /api/v1/admin/categories")
    class GetTree {

        @Test
        @DisplayName("ADMIN 이면 200 — 숨김 포함 트리이고 아이디 · 상위 아이디는 String, 최상위 parentId 는 null 이다")
        void adminIsAllowed() {
            given(categoryAdminUseCase.getCategoryTree()).willReturn(CategoryAdminTreeResponse.builder().categories(List.of(
                CategoryAdminItem.builder().categoryId("1").parentId(null).name("상의").displayOrder(0).visible(true).children(List.of(
                    CategoryAdminItem.builder().categoryId("4611686018427387904").parentId("1").name("숨김").displayOrder(1).visible(false)
                        .children(List.of()).build()
                )).build()
            )).build());

            MvcTestResult result = exchange(HttpMethod.GET, PATH, null, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].categoryId").isEqualTo("1");
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].parentId").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].visible").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].children[0].categoryId").isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].children[0].parentId").isEqualTo("1");
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].children[0].visible").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].children[0].displayOrder").isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].children[0].children").asArray().isEmpty();
        }
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"GET, /api/v1/admin/categories", "POST, /api/v1/admin/categories", "PUT, /api/v1/admin/categories/101", "DELETE, /api/v1/admin/categories/101"})
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(
                exchange(HttpMethod.valueOf(method), uri, VALID_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN
            );
            verifyNoInteractions(categoryAdminUseCase);
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"GET, /api/v1/admin/categories", "POST, /api/v1/admin/categories", "PUT, /api/v1/admin/categories/101", "DELETE, /api/v1/admin/categories/101"})
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, VALID_BODY, null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(categoryAdminUseCase);
        }
    }

    @Nested
    @DisplayName("생성 · 수정 · 삭제")
    class Writes {

        @Test
        @DisplayName("생성은 201 — 문자열 parentId 를 받고 이름 앞뒤 공백을 빼서 넘기며, 아이디는 String 으로 낸다")
        void create() {
            given(categoryAdminUseCase.createCategory(any())).willReturn(response("5001", "1"));

            MvcTestResult result = exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.categoryId").isEqualTo("5001");
            assertThat(result).bodyJson().extractingPath("$.dataBody.parentId").isEqualTo("1");
            assertThat(result).bodyJson().extractingPath("$.dataBody.name").isEqualTo("저고리");
            assertThat(result).bodyJson().extractingPath("$.dataBody.displayOrder").isEqualTo(2);
            assertThat(result).bodyJson().extractingPath("$.dataBody.visible").isEqualTo(false);
            verify(categoryAdminUseCase).createCategory(new CategoryUpsertCommand(1L, "저고리", 2, false));
        }

        @Test
        @DisplayName("parentId 를 빼거나 null 로 보내면 최상위 생성이다")
        void createRoot() {
            given(categoryAdminUseCase.createCategory(any())).willReturn(response("5001", null));

            MvcTestResult result = exchange(HttpMethod.POST, PATH, "{\"name\": \"상의\", \"displayOrder\": 0, \"visible\": true}", MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataBody.parentId").isNull();
            verify(categoryAdminUseCase).createCategory(new CategoryUpsertCommand(null, "상의", 0, true));
        }

        @Test
        @DisplayName("수정은 200 — 경로 아이디와 본문 전체를 넘긴다")
        void update() {
            given(categoryAdminUseCase.updateCategory(anyLong(), any())).willReturn(response("101", "1"));

            MvcTestResult result = exchange(HttpMethod.PUT, ITEM_PATH, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.categoryId").isEqualTo("101");
            verify(categoryAdminUseCase).updateCategory(101L, new CategoryUpsertCommand(1L, "저고리", 2, false));
        }

        @Test
        @DisplayName("삭제는 200 이고 dataBody 는 null 이다")
        void delete() {
            MvcTestResult result = exchange(HttpMethod.DELETE, ITEM_PATH, null, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verify(categoryAdminUseCase).deleteCategory(101L);
        }
    }

    @Nested
    @DisplayName("도메인 오류")
    class DomainErrors {

        @Test
        @DisplayName("없는 카테고리 수정이면 404 · PRODUCT_004")
        void notFound() {
            given(categoryAdminUseCase.updateCategory(anyLong(), any())).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_CATEGORY));

            assertFailure(exchange(HttpMethod.PUT, ITEM_PATH, VALID_BODY, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "PRODUCT_004", "존재하지 않는 카테고리입니다.");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "CATEGORY_DEPTH_EXCEEDED | PRODUCT_040 | 카테고리는 3단계까지만 만들 수 있습니다.",
            "CATEGORY_CYCLE | PRODUCT_041 | 자기 자신이나 하위 카테고리를 상위로 지정할 수 없습니다.",
            "CATEGORY_PARENT_NOT_FOUND | PRODUCT_044 | 상위 카테고리가 존재하지 않습니다."
        })
        @DisplayName("깊이 초과 · 순환 · 없는 상위는 400")
        void badRequest(ProductErrorCode errorCode, String code, String message) {
            given(categoryAdminUseCase.updateCategory(anyLong(), any())).willThrow(new ProductException(errorCode));

            assertFailure(exchange(HttpMethod.PUT, ITEM_PATH, VALID_BODY, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, code, message);
        }

        @Test
        @DisplayName("다른 카테고리 쓰기가 잠금을 쥐고 있어 상한을 넘기면 409 · PRODUCT_045")
        void busy() {
            given(categoryAdminUseCase.createCategory(any())).willThrow(new ProductException(ProductErrorCode.CATEGORY_BUSY));

            assertFailure(exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN), HttpStatus.CONFLICT, "PRODUCT_045", "다른 카테고리 변경을 처리 중입니다. 다시 시도해 주세요.");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "CATEGORY_HAS_CHILDREN | PRODUCT_042 | 하위 카테고리가 있어 삭제할 수 없습니다.",
            "CATEGORY_HAS_PRODUCTS | PRODUCT_043 | 카테고리에 연결된 상품이 있어 삭제할 수 없습니다."
        })
        @DisplayName("하위 · 연결 상품이 있는 삭제는 409")
        void conflict(ProductErrorCode errorCode, String code, String message) {
            willThrow(new ProductException(errorCode)).given(categoryAdminUseCase).deleteCategory(101L);

            assertFailure(exchange(HttpMethod.DELETE, ITEM_PATH, null, MemberRole.ADMIN), HttpStatus.CONFLICT, code, message);
        }
    }

    @Nested
    @DisplayName("요청 검증")
    class Validation {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "{\"parentId\": 0, \"name\": \"a\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_131 | parentId | parentId 는 양수여야 합니다.",
            "{\"parentId\": -1, \"name\": \"a\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_131 | parentId | parentId 는 양수여야 합니다.",
            "{\"displayOrder\": 0, \"visible\": true} | PRODUCT_132 | name | name 은 필수입니다.",
            "{\"name\": \"   \", \"displayOrder\": 0, \"visible\": true} | PRODUCT_132 | name | name 은 필수입니다.",
            "{\"name\": \"\\u3000\\u3000\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_132 | name | name 은 필수입니다.",
            "{\"name\": \" \\u200B \", \"displayOrder\": 0, \"visible\": true} | PRODUCT_132 | name | name 은 필수입니다.",
            "{\"name\": \"NAME_51\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_133 | name | name 은 앞뒤 공백을 뺀 뒤 50자 이하여야 합니다.",
            "{\"name\": \"저\\u0000고리\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_138 | name | NAME_138",
            "{\"name\": \"저\\n고리\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_138 | name | NAME_138",
            "{\"name\": \"\\t저고리\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_138 | name | NAME_138",
            "{\"name\": \"저\\u200B고리\", \"displayOrder\": 0, \"visible\": true} | PRODUCT_138 | name | NAME_138",
            "{\"name\": \"a\", \"visible\": true} | PRODUCT_134 | displayOrder | displayOrder 는 필수입니다.",
            "{\"name\": \"a\", \"displayOrder\": -1, \"visible\": true} | PRODUCT_135 | displayOrder | displayOrder 는 0 이상이어야 합니다.",
            "{\"name\": \"a\", \"displayOrder\": 0} | PRODUCT_136 | visible | visible 은 필수입니다."
        })
        @DisplayName("본문 필드별 검증 코드 — 생성 · 수정 공통")
        void fieldErrors(String body, String code, String field, String expectedMessage) {
            String resolved = body.replace("NAME_51", "가".repeat(51));
            String message = expectedMessage.replace("NAME_138", "name 에는 제어 문자 · 서식 문자(개행 · 탭 · 제로폭 공백 등)를 쓸 수 없습니다.");
            for (MvcTestResult result : List.of(
                exchange(HttpMethod.POST, PATH, resolved, MemberRole.ADMIN), exchange(HttpMethod.PUT, ITEM_PATH, resolved, MemberRole.ADMIN)
            )) {
                assertFailure(result, HttpStatus.BAD_REQUEST, code, message);
                assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
                assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            }
            verifyNoInteractions(categoryAdminUseCase);
        }

        @Test
        @DisplayName("이름 50자 · 노출 순서 0 은 통과한다 (경계값)")
        void boundariesPass() {
            given(categoryAdminUseCase.createCategory(any())).willReturn(response("5001", null));

            assertThat(exchange(HttpMethod.POST, PATH, "{\"name\": \"" + "가".repeat(50) + "\", \"displayOrder\": 0, \"visible\": true}", MemberRole.ADMIN))
                .hasStatus(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("50자는 앞뒤 공백(전각 포함)을 뺀 뒤 센다 — 공백을 붙인 50자는 통과하고, 뺀 값을 넘긴다")
        void lengthCountsAfterStrip() {
            given(categoryAdminUseCase.createCategory(any())).willReturn(response("5001", null));
            String fifty = "가".repeat(50);

            assertThat(exchange(HttpMethod.POST, PATH, "{\"name\": \"\\u3000 " + fifty + " \", \"displayOrder\": 0, \"visible\": true}", MemberRole.ADMIN))
                .hasStatus(HttpStatus.CREATED);
            verify(categoryAdminUseCase).createCategory(new CategoryUpsertCommand(null, fifty, 0, true));
        }

        @Test
        @DisplayName("여러 오류가 겹치면 필드 선언 순서로 첫 오류가 대표 코드다")
        void ordering() {
            MvcTestResult result = exchange(HttpMethod.POST, PATH, "{\"parentId\": 0}", MemberRole.ADMIN);

            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_131");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].code").asArray()
                .containsExactly("PRODUCT_131", "PRODUCT_132", "PRODUCT_134", "PRODUCT_136");
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"PUT, /api/v1/admin/categories/0", "PUT, /api/v1/admin/categories/-1", "DELETE, /api/v1/admin/categories/0"})
        @DisplayName("경로 categoryId 가 양수가 아니면 400 · PRODUCT_137")
        void categoryIdNotPositive(String method, String uri) {
            MvcTestResult result = exchange(HttpMethod.valueOf(method), uri, VALID_BODY, MemberRole.ADMIN);

            assertFailure(result, HttpStatus.BAD_REQUEST, "PRODUCT_137", "categoryId 는 양수여야 합니다.");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("categoryId");
            verifyNoInteractions(categoryAdminUseCase);
        }

        @Test
        @DisplayName("경로 변수와 본문이 함께 틀리면 둘 다 알린다 — 본문 필드 이름 · 코드는 그대로다")
        void categoryIdAndBodyInvalid() {
            MvcTestResult result = exchange(HttpMethod.PUT, "/api/v1/admin/categories/0", "{\"name\": \"a\", \"displayOrder\": -1, \"visible\": true}", MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].code").asArray()
                .containsExactlyInAnyOrder("PRODUCT_137", "PRODUCT_135");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].field").asArray()
                .containsExactlyInAnyOrder("categoryId", "displayOrder");
            verifyNoInteractions(categoryAdminUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"PUT", "DELETE"})
        @DisplayName("경로 categoryId 가 숫자가 아니면 400 · PRODUCT_198")
        void categoryIdNotNumber(String method) {
            assertFailure(
                exchange(HttpMethod.valueOf(method), "/api/v1/admin/categories/abc", VALID_BODY, MemberRole.ADMIN),
                HttpStatus.BAD_REQUEST, "PRODUCT_198", "categoryId 파라미터 형식이 올바르지 않습니다."
            );
            verifyNoInteractions(categoryAdminUseCase);
        }

        @Test
        @DisplayName("깨진 JSON · 숫자가 아닌 parentId 는 400 · PRODUCT_100 이고 Response 봉투다")
        void unreadable() {
            for (String body : List.of("{\"name\": \"a\",", "{\"parentId\": \"abc\", \"name\": \"a\", \"displayOrder\": 0, \"visible\": true}")) {
                MvcTestResult result = exchange(HttpMethod.POST, PATH, body, MemberRole.ADMIN);

                assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
                assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
                assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_100");
            }
            verifyNoInteractions(categoryAdminUseCase);
        }
    }

    private static CategoryAdminResponse response(String categoryId, String parentId) {
        return CategoryAdminResponse.builder().categoryId(categoryId).parentId(parentId).name("저고리").displayOrder(2).visible(false).build();
    }

    /** {@code role} 이 null 이면 토큰 없이, {@code body} 가 null 이면 본문 없이 보낸다. */
    private MvcTestResult exchange(HttpMethod method, String uri, String body, MemberRole role) {
        var request = mvc.method(method).uri(uri);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, bearer(role));
        }
        if (body != null && (method == HttpMethod.POST || method == HttpMethod.PUT)) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private static void assertFailure(MvcTestResult result, HttpStatus status, String code, String message) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }
}
