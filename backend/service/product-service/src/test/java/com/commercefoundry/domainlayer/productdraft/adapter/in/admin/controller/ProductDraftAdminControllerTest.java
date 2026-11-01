package com.commercefoundry.domainlayer.productdraft.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.presenter.ProductDraftAdminPresenter;
import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftAdminUseCase;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
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
 * 어드민 AI 상품 초안의 HTTP 계약 — 권한(ADMIN 만), 만든 · 거절한 어드민은 토큰에서, 검증 오류 코드 봉투(PRODUCT_DRAFT_1xx · 폴백 PRODUCT_100),
 * 도메인 오류 봉투, 응답 모양(아이디 String · 상태 metadata · 내부 정보 없음 · 201).
 */
@WebMvcTest(ProductDraftAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ProductDraftAdminControllerTest {

    private static final String PATH = "/api/v1/admin/product-drafts";
    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final String VALID_BODY = """
        {"nameHint": "  린넨 저고리  ", "categoryId": "12", "materials": [" 린넨 100% "], "measurementsNote": "총장 62cm\\n가슴단면 54cm", "tone": "   ",
         "images": [{"url": " https://cdn.example.com/media/a.jpg ", "imageType": "THUMBNAIL", "width": 1200}]}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductDraftAdminUseCase productDraftAdminUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({"POST, /api/v1/admin/product-drafts", "GET, /api/v1/admin/product-drafts/1", "POST, /api/v1/admin/product-drafts/1/reject"})
        @DisplayName("CUSTOMER 면 403 · SECURITY_005, 토큰이 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void onlyAdmin(String method, String uri) {
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, VALID_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(exchange(HttpMethod.valueOf(method), uri, VALID_BODY, null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(productDraftAdminUseCase);
        }
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("201 — 만든 어드민은 토큰의 memberId, 문구는 앞뒤 공백을 빼고 빈 문구는 null, 응답에 상태 metadata · 누락 입력이 있고 내부 정보는 없다")
        void creates() {
            given(productDraftAdminUseCase.create(any())).willReturn(response());

            MvcTestResult result = exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            ArgumentCaptor<ProductDraftCreateCommand> command = ArgumentCaptor.forClass(ProductDraftCreateCommand.class);
            verify(productDraftAdminUseCase).create(command.capture());
            assertThat(command.getValue().requestedByMemberId()).isEqualTo(TestTokens.MEMBER_ID);
            assertThat(command.getValue().facts().nameHint()).isEqualTo("린넨 저고리");
            assertThat(command.getValue().facts().categoryId()).isEqualTo(12L);
            assertThat(command.getValue().facts().materials()).containsExactly("린넨 100%");
            assertThat(command.getValue().facts().measurementsNote()).isEqualTo("총장 62cm\n가슴단면 54cm");
            assertThat(command.getValue().facts().tone()).isNull();
            assertThat(command.getValue().images()).containsExactly(new ProductDraftImage("https://cdn.example.com/media/a.jpg", ProductDraftImageType.THUMBNAIL, 1200, null));

            assertThat(result).bodyJson().extractingPath("$.dataBody.draftId").isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("REQUESTED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.name").isEqualTo("생성 대기");
            assertThat(result).bodyJson().extractingPath("$.dataBody.missingRequiredInputs[0].code").isEqualTo("PRODUCT_NAME");
            assertThat(result).bodyJson().extractingPath("$.dataBody.facts.categoryId").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.requestedByMemberId").isEqualTo("100");
            assertThat(result).bodyJson().extractingPath("$.dataBody").asMap()
                .doesNotContainKeys("version", "inputJson", "model", "prompt", "jobId", "rejectedByMemberId");
        }

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', value = {
            "{\"nameHint\": \"a\\u0007b\"}|PRODUCT_DRAFT_102",
            "{\"nameHint\": \"a\\u0085b\"}|PRODUCT_DRAFT_102",
            "{\"nameHint\": \"a\\u202Eb\"}|PRODUCT_DRAFT_102",
            "{\"nameHint\": \"a\\udb40\\udc01b\"}|PRODUCT_DRAFT_102",
            "{\"nameHint\": \"a\\ud800b\"}|PRODUCT_DRAFT_102",
            "{\"nameHint\": \"a\\nb\"}|PRODUCT_DRAFT_102",
            "{\"tone\": \"a\\u2028b\"}|PRODUCT_DRAFT_115",
            "{\"categoryId\": 0}|PRODUCT_DRAFT_103",
            "{\"materials\": [\"\"]}|PRODUCT_DRAFT_105",
            "{\"materials\": [\"a\\u0007\"]}|PRODUCT_DRAFT_105",
            "{\"measurementsNote\": \"a\\u0000\"}|PRODUCT_DRAFT_109",
            "{\"measurementsNote\": \"a\\u0085b\"}|PRODUCT_DRAFT_109",
            "{\"images\": [{\"url\": \"https://cdn.example.com/media/a.jpg\"}]}|PRODUCT_DRAFT_124",
            "{\"images\": [{\"url\": \"https://cdn.example.com/media/a.jpg\", \"imageType\": \"GALLERY\", \"width\": 0}]}|PRODUCT_DRAFT_125",
            "{\"images\": [null]}|PRODUCT_DRAFT_121",
            "{\"images\": [{\"url\": \"https://cdn.example.com/media/a.jpg\", \"imageType\": \"VIDEO\"}]}|PRODUCT_100"
        })
        @DisplayName("검증 실패는 400 · 필드 코드이고 유스케이스를 부르지 않는다 — C1 · 방향 · 태그 · 짝 없는 서로게이트 · 줄 구분자도 막고, 영상 유형은 서비스 공용 PRODUCT_100")
        void validation(String body, String code) {
            MvcTestResult result = exchange(HttpMethod.POST, PATH, body, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            verifyNoInteractions(productDraftAdminUseCase);
        }

        @Test
        @DisplayName("상한 — nameHint 101자는 101, 이미지 9장은 120 이다. 치수 메모의 CRLF · 탭 · 이모지(ZWJ)는 받는다")
        void limitsAndAllowedCharacters() {
            assertThat(exchange(HttpMethod.POST, PATH, "{\"nameHint\": \"" + "가".repeat(101) + "\"}", MemberRole.ADMIN))
                .bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_DRAFT_101");
            String image = "{\"url\": \"https://cdn.example.com/media/a.jpg\", \"imageType\": \"GALLERY\"}";
            assertThat(exchange(HttpMethod.POST, PATH, "{\"images\": [" + String.join(",", Collections.nCopies(9, image)) + "]}", MemberRole.ADMIN))
                .bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_DRAFT_120");
            verifyNoInteractions(productDraftAdminUseCase);

            given(productDraftAdminUseCase.create(any())).willReturn(response());
            assertThat(exchange(HttpMethod.POST, PATH, "{\"measurementsNote\": \"총장 62cm\\r\\n\\t가슴 54cm\", \"tone\": \"밝게 \\ud83d\\udc68\\u200d\\ud83d\\udc69\"}",
                MemberRole.ADMIN)).hasStatus(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("이미지 판정 실패는 도메인 봉투 — 400 · PRODUCT_DRAFT_030")
        void imageRefused() {
            given(productDraftAdminUseCase.create(any())).willThrow(new ProductDraftException(ProductDraftErrorCode.IMAGE_URL_NOT_ALLOWED));

            assertFailure(exchange(HttpMethod.POST, PATH, VALID_BODY, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "PRODUCT_DRAFT_030",
                "이미지 주소는 스토어 미디어 저장소의 공개 주소여야 합니다.");
        }
    }

    @Nested
    @DisplayName("조회 · 거절")
    class ReadAndReject {

        @Test
        @DisplayName("없는 초안은 404 · PRODUCT_DRAFT_001, 경로 아이디가 양수가 아니면 400 · PRODUCT_DRAFT_127 이다")
        void notFound() {
            given(productDraftAdminUseCase.getDraft(9L)).willThrow(new ProductDraftException(ProductDraftErrorCode.NOT_FOUND_DRAFT));

            assertFailure(exchange(HttpMethod.GET, PATH + "/9", null, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "PRODUCT_DRAFT_001", "존재하지 않는 초안입니다.");
            assertThat(exchange(HttpMethod.GET, PATH + "/0", null, MemberRole.ADMIN)).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_DRAFT_127");
        }

        @Test
        @DisplayName("거절은 200 이고 거절한 어드민은 토큰의 memberId 다. 적용한 초안은 409 · PRODUCT_DRAFT_020")
        void reject() {
            given(productDraftAdminUseCase.reject(7L, TestTokens.MEMBER_ID)).willReturn(response());
            given(productDraftAdminUseCase.reject(8L, TestTokens.MEMBER_ID)).willThrow(new ProductDraftException(ProductDraftErrorCode.DRAFT_NOT_REJECTABLE));

            given(productDraftAdminUseCase.reject(9L, TestTokens.MEMBER_ID)).willThrow(new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY));

            assertThat(exchange(HttpMethod.POST, PATH + "/7/reject", null, MemberRole.ADMIN)).hasStatusOk();
            assertFailure(exchange(HttpMethod.POST, PATH + "/8/reject", null, MemberRole.ADMIN), HttpStatus.CONFLICT, "PRODUCT_DRAFT_020",
                "이미 적용한 초안은 거절할 수 없습니다.");
            assertFailure(exchange(HttpMethod.POST, PATH + "/9/reject", null, MemberRole.ADMIN), HttpStatus.CONFLICT, "PRODUCT_DRAFT_025",
                "다른 변경과 겹쳤습니다. 다시 확인한 뒤 시도해 주세요.");
            assertThat(exchange(HttpMethod.POST, PATH + "/0/reject", null, MemberRole.ADMIN))
                .bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("PRODUCT_DRAFT_127");
        }
    }

    private static ProductDraftAdminResponse response() {
        ProductDraft draft = new ProductDraft(4611686018427387905L, ProductDraftStatus.REQUESTED, 100L,
            new ProductDraftFacts(null, null, null, null, null, null, null, null, null, null), List.of(), null, null, NOW.plusSeconds(3600), NOW);
        return new ProductDraftAdminPresenter().toResponse(draft, NOW);
    }

    private MvcTestResult exchange(HttpMethod method, String uri, String body, MemberRole role) {
        var request = mvc.method(method).uri(uri);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        if (body != null && method == HttpMethod.POST) {
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
