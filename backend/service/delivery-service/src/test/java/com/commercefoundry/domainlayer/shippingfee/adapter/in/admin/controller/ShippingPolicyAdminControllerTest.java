package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.RemoteAreaAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 국내 배송비 정책 API 의 HTTP 계약 — 권한 · 본문 검증 코드 · 도메인 오류 봉투. 슬라이스라 보안 구성을 직접 import 하고,
 * 인증 주체는 {@code @WithMockUser} 로 넣는다. 실제 JWT 로 어드민 URL 규칙을 거치는 경로는 {@code DeliveryServiceSecuritySmokeTest} 가 본다.
 */
@WebMvcTest(ShippingPolicyAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ShippingPolicyAdminControllerTest {

    private static final String PATH = "/api/v1/admin/shipping-policies/domestic";
    private static final String VALID_BODY = """
        {
          "baseFee": {"amount": 3000, "currency": "KRW"},
          "freeShippingThreshold": {"amount": 50000, "currency": "KRW"},
          "remoteAreas": [
            {"name": "제주", "postalCodeFrom": "63000", "postalCodeTo": "63644", "surcharge": {"amount": 3000, "currency": "KRW"}}
          ],
          "version": 3
        }
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ShippingPolicyAdminUseCase shippingPolicyAdminUseCase;

    private static ShippingPolicyAdminResponse response() {
        return ShippingPolicyAdminResponse.builder()
            .shippingPolicyId("4611686018427387904")
            .baseFee(MoneyItem.of(3_000, "KRW"))
            .freeShippingThreshold(MoneyItem.of(50_000, "KRW"))
            .remoteAreas(List.of(RemoteAreaAdminItem.builder()
                .remoteAreaId("4611686018427387905").name("제주").postalCodeFrom("63000").postalCodeTo("63644").surcharge(MoneyItem.of(3_000, "KRW"))
                .build()))
            .version(4)
            .updatedAt(Instant.parse("2026-10-18T03:00:00Z"))
            .build();
    }

    private MvcTestResult put(String body) {
        return mvc.put().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("ADMIN 이면 조회 200 — 아이디는 문자열, 버전은 숫자, 수정 시각은 ISO-8601 문자열")
        void adminCanRead() {
            given(shippingPolicyAdminUseCase.getDomesticPolicy()).willReturn(response());

            MvcTestResult result = mvc.get().uri(PATH).exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.shippingPolicyId").asString().isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.remoteAreas[0].remoteAreaId").asString().isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.version").asNumber().isEqualTo(4);
            assertThat(result).bodyJson().extractingPath("$.dataBody.updatedAt").asString().isEqualTo("2026-10-18T03:00:00Z");
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("정책이 아직 없으면 200 이고 dataBody 가 null 이다")
        void notConfiguredIsNull() {
            given(shippingPolicyAdminUseCase.getDomesticPolicy()).willReturn(null);

            MvcTestResult result = mvc.get().uri(PATH).exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }

        @Test
        @WithMockUser(roles = "CUSTOMER")
        @DisplayName("CUSTOMER 면 조회 · 교체 모두 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(put(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(shippingPolicyAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(put(VALID_BODY).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(shippingPolicyAdminUseCase);
        }
    }

    @Nested
    @DisplayName("교체")
    @WithMockUser(roles = "ADMIN")
    class Replace {

        @Test
        @DisplayName("본문을 command 로 바꿔 넘기고 200 으로 저장 결과를 낸다")
        void replaces() {
            given(shippingPolicyAdminUseCase.replaceDomesticPolicy(any())).willReturn(response());

            MvcTestResult result = put(VALID_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.version").asNumber().isEqualTo(4);
            ArgumentCaptor<ShippingPolicyReplaceCommand> command = ArgumentCaptor.forClass(ShippingPolicyReplaceCommand.class);
            verify(shippingPolicyAdminUseCase).replaceDomesticPolicy(command.capture());
            assertThat(command.getValue()).isEqualTo(new ShippingPolicyReplaceCommand(
                Money.of(3_000, CurrencyCode.KRW), Money.of(50_000, CurrencyCode.KRW),
                List.of(new RemoteAreaCommand("제주", new PostalCode("63000"), new PostalCode("63644"), Money.of(3_000, CurrencyCode.KRW))),
                3L
            ));
        }

        @Test
        @DisplayName("무료배송 기준 · version 을 비우면 null 로 넘긴다 (처음 만들기)")
        void nullableFields() {
            given(shippingPolicyAdminUseCase.replaceDomesticPolicy(any())).willReturn(response());

            assertThat(put("""
                {"baseFee": {"amount": 0, "currency": "KRW"}, "remoteAreas": []}
                """)).hasStatusOk();
            verify(shippingPolicyAdminUseCase).replaceDomesticPolicy(new ShippingPolicyReplaceCommand(Money.of(0, CurrencyCode.KRW), null, List.of(), null));
        }

        @Test
        @DisplayName("버전 충돌은 409 · DELIVERY_006")
        void versionConflict() {
            given(shippingPolicyAdminUseCase.replaceDomesticPolicy(any())).willThrow(new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT));

            MvcTestResult result = put(VALID_BODY);

            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("DELIVERY_006");
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }

        @Test
        @DisplayName("구간 시작이 끝보다 크면 400 · DELIVERY_004 이고 구간 이름이 메시지에 들어간다")
        void reversedRange() {
            given(shippingPolicyAdminUseCase.replaceDomesticPolicy(any()))
                .willThrow(new DeliveryException(DeliveryErrorCode.REMOTE_AREA_RANGE_INVALID, "거꾸로"));

            MvcTestResult result = put(VALID_BODY);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("DELIVERY_004");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("도서산간 구간의 시작 우편번호가 끝 우편번호보다 큽니다: 거꾸로");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors").isNull();
        }

        @Test
        @DisplayName("구간 겹침은 400 · DELIVERY_005 이고 두 구간 이름이 메시지에 들어간다")
        void overlap() {
            given(shippingPolicyAdminUseCase.replaceDomesticPolicy(any()))
                .willThrow(new DeliveryException(DeliveryErrorCode.REMOTE_AREA_RANGE_OVERLAP, "제주", "제주 일부"));

            MvcTestResult result = put(VALID_BODY);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("DELIVERY_005");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("도서산간 구간이 겹칩니다: 제주, 제주 일부");
        }
    }

    @Nested
    @DisplayName("본문 검증")
    @WithMockUser(roles = "ADMIN")
    class BodyValidation {

        @Test
        @DisplayName("baseFee 가 없으면 400 · DELIVERY_109")
        void baseFeeMissing() {
            assertBadRequest(put("""
                {"remoteAreas": []}
                """), "DELIVERY_109", "baseFee");
        }

        @Test
        @DisplayName("금액이 음수면 400 · DELIVERY_103 — 필드는 baseFee.amount")
        void negativeAmount() {
            assertBadRequest(put("""
                {"baseFee": {"amount": -1, "currency": "KRW"}, "remoteAreas": []}
                """), "DELIVERY_103", "baseFee.amount");
        }

        @Test
        @DisplayName("금액이 빠지면 0 으로 들어가지 않고 400 · DELIVERY_102")
        void amountMissing() {
            assertBadRequest(put("""
                {"baseFee": {"currency": "KRW"}, "remoteAreas": []}
                """), "DELIVERY_102", "baseFee.amount");
        }

        @Test
        @DisplayName("금액이 상한(100,000,000)을 넘으면 400 · DELIVERY_117 — 기본 · 기준 · 추가 배송비 모두 같은 코드")
        void amountOverMax() {
            assertBadRequest(put("""
                {"baseFee": {"amount": 100000001, "currency": "KRW"}, "remoteAreas": []}
                """), "DELIVERY_117", "baseFee.amount");
        }

        @Test
        @DisplayName("상한 초과는 무료배송 기준 · 추가 배송비 필드도 DELIVERY_117 이다")
        void amountOverMaxOnOtherFields() {
            assertBadRequest(put("""
                {"baseFee": {"amount": 3000, "currency": "KRW"}, "freeShippingThreshold": {"amount": 100000001, "currency": "KRW"}, "remoteAreas": []}
                """), "DELIVERY_117", "freeShippingThreshold.amount");
            assertBadRequest(put("""
                {"baseFee": {"amount": 3000, "currency": "KRW"},
                 "remoteAreas": [{"name": "제주", "postalCodeFrom": "63000", "postalCodeTo": "63644", "surcharge": {"amount": 100000001, "currency": "KRW"}}]}
                """), "DELIVERY_117", "remoteAreas[0].surcharge.amount");
        }

        @Test
        @DisplayName("상한과 같은 금액(100,000,000)은 받는다")
        void amountAtMax() {
            given(shippingPolicyAdminUseCase.replaceDomesticPolicy(any())).willReturn(response());

            assertThat(put("""
                {"baseFee": {"amount": 100000000, "currency": "KRW"}, "remoteAreas": []}
                """)).hasStatusOk();
        }

        @Test
        @DisplayName("무료배송 기준 0원은 400 · DELIVERY_110")
        void zeroThreshold() {
            assertBadRequest(put("""
                {"baseFee": {"amount": 3000, "currency": "KRW"}, "freeShippingThreshold": {"amount": 0, "currency": "KRW"}, "remoteAreas": []}
                """), "DELIVERY_110", "freeShippingThreshold.amount");
        }

        @Test
        @DisplayName("remoteAreas 가 없으면 400 · DELIVERY_111 — 구간이 없으면 빈 배열을 보낸다")
        void remoteAreasMissing() {
            assertBadRequest(put("""
                {"baseFee": {"amount": 3000, "currency": "KRW"}}
                """), "DELIVERY_111", "remoteAreas");
        }

        @Test
        @DisplayName("구간이 201개면 400 · DELIVERY_112")
        void tooManyAreas() {
            String areas = IntStream.range(0, 201)
                .mapToObj(index -> "{\"name\": \"구간%d\", \"postalCodeFrom\": \"%05d\", \"postalCodeTo\": \"%05d\", \"surcharge\": {\"amount\": 1000, \"currency\": \"KRW\"}}"
                    .formatted(index, index, index))
                .collect(Collectors.joining(","));

            assertBadRequest(put("{\"baseFee\": {\"amount\": 3000, \"currency\": \"KRW\"}, \"remoteAreas\": [" + areas + "]}"), "DELIVERY_112", "remoteAreas");
        }

        @Test
        @DisplayName("구간 원소가 null 이면 400 · DELIVERY_113")
        void nullArea() {
            assertBadRequest(put("""
                {"baseFee": {"amount": 3000, "currency": "KRW"}, "remoteAreas": [null]}
                """), "DELIVERY_113", "remoteAreas[0]");
        }

        @Test
        @DisplayName("구간 이름이 비면 400 · DELIVERY_114")
        void blankName() {
            assertBadRequest(put(area("\" \"", "\"63000\"", "\"63644\"")), "DELIVERY_114", "remoteAreas[0].name");
        }

        @Test
        @DisplayName("구간 이름이 51자면 400 · DELIVERY_115")
        void longName() {
            assertBadRequest(put(area("\"" + "가".repeat(51) + "\"", "\"63000\"", "\"63644\"")), "DELIVERY_115", "remoteAreas[0].name");
        }

        @Test
        @DisplayName("우편번호가 숫자 5자리가 아니면 400 · DELIVERY_108")
        void invalidPostalCode() {
            assertBadRequest(put(area("\"제주\"", "\"6300\"", "\"63644\"")), "DELIVERY_108", "remoteAreas[0].postalCodeFrom");
        }

        @Test
        @DisplayName("우편번호가 없으면 400 · DELIVERY_107")
        void missingPostalCode() {
            assertBadRequest(put(area("\"제주\"", "\"63000\"", "null")), "DELIVERY_107", "remoteAreas[0].postalCodeTo");
        }

        @Test
        @DisplayName("추가 배송비가 없으면 400 · DELIVERY_116")
        void missingSurcharge() {
            assertBadRequest(put("""
                {"baseFee": {"amount": 3000, "currency": "KRW"}, "remoteAreas": [{"name": "제주", "postalCodeFrom": "63000", "postalCodeTo": "63644"}]}
                """), "DELIVERY_116", "remoteAreas[0].surcharge");
        }

        @Test
        @DisplayName("없는 통화(JPY)는 400 · DELIVERY_100 이고 필드와 허용 값을 알린다")
        void unknownCurrency() {
            MvcTestResult result = put("""
                {"baseFee": {"amount": 3000, "currency": "JPY"}, "remoteAreas": []}
                """);

            assertBadRequest(result, "DELIVERY_100", "baseFee.currency");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").asString().contains("KRW", "USD");
        }

        @Test
        @DisplayName("깨진 JSON 은 400 · DELIVERY_100")
        void brokenJson() {
            assertBadRequest(put("{\"baseFee\": "), "DELIVERY_100", "request");
        }

        private static String area(String name, String from, String to) {
            return """
                {"baseFee": {"amount": 3000, "currency": "KRW"},
                 "remoteAreas": [{"name": %s, "postalCodeFrom": %s, "postalCodeTo": %s, "surcharge": {"amount": 3000, "currency": "KRW"}}]}
                """.formatted(name, from, to);
        }

        private void assertBadRequest(MvcTestResult result, String code, String field) {
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(shippingPolicyAdminUseCase);
        }
    }
}
