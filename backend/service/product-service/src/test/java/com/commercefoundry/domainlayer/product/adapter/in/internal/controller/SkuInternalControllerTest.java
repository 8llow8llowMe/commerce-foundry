package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.SkuInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.SkuInternalResponse;
import com.commercefoundry.domainlayer.product.application.port.in.SkuInternalUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 서비스 간 SKU 벌크 조회의 HTTP 계약 — 권한과 {@code ids} 검증 코드. 서비스 토큰(#51)이 아직 없어 인증 주체는 {@code @WithMockUser} 로 넣는다.
 * 익명 응답 코드는 단언하지 않는다 — #51 의 서비스 토큰 필터가 들어오면 바뀔 수 있다.
 */
@WebMvcTest(SkuInternalController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class SkuInternalControllerTest {

    private static final String PATH = "/internal/v1/skus";
    private static final String ORDER_SERVICE = "SERVICE_order-service";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private SkuInternalUseCase skuInternalUseCase;

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @WithMockUser(authorities = ORDER_SERVICE)
        @DisplayName("order-service 권한이면 200 이고 Response 봉투다 — 요청 순서 그대로 넘긴다")
        void orderServiceIsAllowed() {
            given(skuInternalUseCase.getSkus(List.of(9004L, 9001L, 9004L))).willReturn(SkuInternalResponse.builder().skus(List.of(SkuInternalItem.builder()
                .skuId("9004")
                .productId("7301")
                .productName("저고리")
                .optionValues(List.of())
                .optionName("")
                .salePrice(MoneyItem.of(44_000, "KRW"))
                .additionalPrice(MoneyItem.of(5_000, "KRW"))
                .saleStatus(CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "구매할 수 있습니다."))
                .purchasable(true)
                .customOrder(false)
                .build())).build());

            MvcTestResult result = mvc.get().uri(PATH).param("ids", "9004,9001,9004").exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].skuId").isEqualTo("9004");
            assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].productId").isEqualTo("7301");
            assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].salePrice.amount").isEqualTo(44_000);
            assertThat(result).bodyJson().extractingPath("$.dataBody.skus[0].maxQuantityPerOrder").isNull();
            verify(skuInternalUseCase).getSkus(List.of(9004L, 9001L, 9004L));
        }

        @Test
        @WithMockUser(authorities = ORDER_SERVICE)
        @DisplayName("ids 를 여러 번 나눠 보내도(ids=1&ids=2) 같은 목록이다")
        void repeatedParameter() {
            given(skuInternalUseCase.getSkus(List.of(1L, 2L))).willReturn(SkuInternalResponse.builder().skus(List.of()).build());

            assertThat(mvc.get().uri(PATH + "?ids=1&ids=2")).hasStatusOk();
            verify(skuInternalUseCase).getSkus(List.of(1L, 2L));
        }

        @Test
        @WithMockUser(authorities = "SERVICE_payment-service")
        @DisplayName("다른 서비스 권한이면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void otherServiceIsForbidden() {
            MvcTestResult result = mvc.get().uri(PATH).param("ids", "1").exchange();

            SecurityEnvelopeAssertions.assertEnvelope(result.getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(skuInternalUseCase);
        }

        @Test
        @WithMockUser(roles = "CUSTOMER")
        @DisplayName("회원 권한(ROLE_CUSTOMER)이면 403 · SECURITY_005 다")
        void memberIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).param("ids", "1").exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(skuInternalUseCase);
        }

        /**
         * 인증을 통과한 호출자 기준으로 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다. 인증 전 단계(#51 internal 체인)의 401 은 여기서 보지 않는다.
         * 컨트롤러 주석 · {@code api-design-guide.md} §1-3 이 이 순서를 적는다.
         */
        @Test
        @WithMockUser(authorities = "SERVICE_payment-service")
        @DisplayName("현재 동작 기록 — 인증된 호출자 기준: 허용되지 않은 호출자도 형식이 틀리면 403 이 아니라 400 이다")
        void validationRunsBeforeAuthorization() {
            assertThat(mvc.get().uri(PATH).param("ids", "0")).hasStatus(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(skuInternalUseCase);
        }
    }

    @Nested
    @DisplayName("ids 검증")
    @WithMockUser(authorities = ORDER_SERVICE)
    class IdsValidation {

        @Test
        @DisplayName("파라미터가 없으면 400 · PRODUCT_199")
        void missing() {
            assertBadRequest(mvc.get().uri(PATH).exchange(), "PRODUCT_199", "ids 파라미터는 필수입니다.");
        }

        @Test
        @DisplayName("ids= (빈 목록)이면 400 · PRODUCT_103")
        void empty() {
            assertBadRequest(mvc.get().uri(PATH).param("ids", "").exchange(), "PRODUCT_103", "ids 는 필수입니다.");
        }

        @Test
        @DisplayName("101개(중복 포함 원문 개수)면 400 · PRODUCT_104")
        void tooMany() {
            String ids = LongStream.rangeClosed(1, 100).mapToObj(String::valueOf).collect(Collectors.joining(",")) + ",1";

            assertBadRequest(mvc.get().uri(PATH).param("ids", ids).exchange(), "PRODUCT_104", "ids 는 최대 100개입니다.");
        }

        @Test
        @DisplayName("100개는 받는다")
        void hundredIsAllowed() {
            List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
            given(skuInternalUseCase.getSkus(ids)).willReturn(SkuInternalResponse.builder().skus(List.of()).build());

            assertThat(mvc.get().uri(PATH).param("ids", ids.stream().map(String::valueOf).collect(Collectors.joining(",")))).hasStatusOk();
        }

        @Test
        @DisplayName("0 이면 400 · PRODUCT_105")
        void zero() {
            assertBadRequest(mvc.get().uri(PATH).param("ids", "0").exchange(), "PRODUCT_105", "id 는 양수여야 합니다.");
        }

        @Test
        @DisplayName("음수가 섞이면 400 · PRODUCT_105")
        void negative() {
            assertBadRequest(mvc.get().uri(PATH).param("ids", "1,-3").exchange(), "PRODUCT_105", "id 는 양수여야 합니다.");
        }

        @Test
        @DisplayName("빈 원소(ids=1,,2)는 null 이 되어 400 · PRODUCT_105 다")
        void blankElement() {
            assertBadRequest(mvc.get().uri(PATH).param("ids", "1,,2").exchange(), "PRODUCT_105", "id 는 양수여야 합니다.");
        }

        @Test
        @DisplayName("숫자가 아니면 400 · PRODUCT_198")
        void notNumber() {
            assertBadRequest(mvc.get().uri(PATH).param("ids", "a").exchange(), "PRODUCT_198", "ids 파라미터 형식이 올바르지 않습니다.");
        }

        private void assertBadRequest(MvcTestResult result, String code, String message) {
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo(message);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("ids");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(skuInternalUseCase);
        }
    }
}
