package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingZoneAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingZoneRateAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.RateCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.ZoneCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.InternationalShippingPolicyAdminUseCase;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * 어드민 해외 배송비 정책 API 의 HTTP 계약 — 권한 · 본문 검증 코드 · 도메인 오류 봉투. 구성은 국내 {@link ShippingPolicyAdminControllerTest} 와 같다.
 */
@WebMvcTest(InternationalShippingPolicyAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class InternationalShippingPolicyAdminControllerTest {

    private static final String PATH = "/api/v1/admin/shipping-policies/international";
    private static final String VALID_BODY = """
        {
          "baseFee": {"amount": 500, "currency": "USD"},
          "freeShippingThreshold": {"amount": 20000, "currency": "USD"},
          "customsNotice": "  관세는 받는 분 부담  ",
          "zones": [
            {"name": "아시아", "countryCodes": ["JP", "SG"], "rates": [{"maxWeightGrams": 500, "fee": {"amount": 1000, "currency": "USD"}}]}
          ],
          "version": 2
        }
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private InternationalShippingPolicyAdminUseCase internationalShippingPolicyAdminUseCase;

    private static InternationalShippingPolicyAdminResponse response() {
        return InternationalShippingPolicyAdminResponse.builder()
            .shippingPolicyId("4611686018427387904")
            .baseFee(MoneyItem.of(500, "USD"))
            .zones(List.of(ShippingZoneAdminItem.builder()
                .shippingZoneId("4611686018427387905").name("아시아").countryCodes(List.of("JP"))
                .rates(List.of(ShippingZoneRateAdminItem.builder().shippingZoneRateId("4611686018427387906").maxWeightGrams(500).fee(MoneyItem.of(1_000, "USD")).build()))
                .build()))
            .version(3)
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
        @DisplayName("ADMIN 이면 조회 200 — 아이디는 문자열이다")
        void adminCanRead() {
            given(internationalShippingPolicyAdminUseCase.getInternationalPolicy()).willReturn(response());

            MvcTestResult result = mvc.get().uri(PATH).exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.shippingPolicyId").asString().isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.zones[0].shippingZoneId").asString().isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.zones[0].rates[0].shippingZoneRateId").asString().isEqualTo("4611686018427387906");
            assertThat(result).bodyJson().extractingPath("$.dataBody.version").asNumber().isEqualTo(3);
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("정책이 아직 없으면 200 이고 dataBody 가 null 이다")
        void notConfiguredIsNull() {
            given(internationalShippingPolicyAdminUseCase.getInternationalPolicy()).willReturn(null);

            assertThat(mvc.get().uri(PATH).exchange()).hasStatusOk().bodyJson().extractingPath("$.dataBody").isNull();
        }

        @Test
        @WithMockUser(roles = "CUSTOMER")
        @DisplayName("CUSTOMER 면 조회 · 교체 모두 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(put(VALID_BODY).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(internationalShippingPolicyAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(put(VALID_BODY).getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(internationalShippingPolicyAdminUseCase);
        }
    }

    @Nested
    @DisplayName("교체")
    @WithMockUser(roles = "ADMIN")
    class Replace {

        @Test
        @DisplayName("본문을 command 로 바꿔 넘긴다 — 안내 문구는 앞뒤 공백을 자른다")
        void replaces() {
            given(internationalShippingPolicyAdminUseCase.replaceInternationalPolicy(any())).willReturn(response());

            assertThat(put(VALID_BODY)).hasStatusOk();
            verify(internationalShippingPolicyAdminUseCase).replaceInternationalPolicy(new InternationalShippingPolicyReplaceCommand(
                Money.of(500, CurrencyCode.USD), Money.of(20_000, CurrencyCode.USD), "관세는 받는 분 부담",
                List.of(new ZoneCommand("아시아", List.of("JP", "SG"), List.of(new RateCommand(500, Money.of(1_000, CurrencyCode.USD))))),
                2L
            ));
        }

        @Test
        @DisplayName("무료배송 기준 · 안내 문구(공백뿐) · version 은 null 로 넘긴다 (처음 만들기)")
        void nullableFields() {
            given(internationalShippingPolicyAdminUseCase.replaceInternationalPolicy(any())).willReturn(response());

            assertThat(put("""
                {"baseFee": {"amount": 0, "currency": "USD"}, "customsNotice": "   ",
                 "zones": [{"name": "북미", "countryCodes": ["US"], "rates": [{"maxWeightGrams": 1000, "fee": {"amount": 2000, "currency": "USD"}}]}]}
                """)).hasStatusOk();
            verify(internationalShippingPolicyAdminUseCase).replaceInternationalPolicy(new InternationalShippingPolicyReplaceCommand(
                Money.of(0, CurrencyCode.USD), null, null,
                List.of(new ZoneCommand("북미", List.of("US"), List.of(new RateCommand(1_000, Money.of(2_000, CurrencyCode.USD))))), null
            ));
        }

        @ParameterizedTest(name = "{0} → {1} {2}")
        @CsvSource({
            "SHIPPING_POLICY_VERSION_CONFLICT, 409, DELIVERY_006",
            "SHIPPING_POLICY_CURRENCY_CHANGE_NOT_ALLOWED, 409, DELIVERY_012",
            "CURRENCY_MISMATCH, 400, DELIVERY_003",
            "SHIPPING_ZONE_COUNTRY_DUPLICATED, 400, DELIVERY_007",
            "SHIPPING_ZONE_RATE_DUPLICATED, 400, DELIVERY_008",
            "SHIPPING_ZONE_DOMESTIC_COUNTRY, 400, DELIVERY_009",
            "SHIPPING_ZONE_NAME_DUPLICATED, 400, DELIVERY_010"
        })
        @DisplayName("도메인 오류는 상태 · 코드 봉투로 나간다")
        void domainErrors(DeliveryErrorCode errorCode, int status, String code) {
            given(internationalShippingPolicyAdminUseCase.replaceInternationalPolicy(any())).willThrow(new DeliveryException(errorCode, "가", "나"));

            MvcTestResult result = put(VALID_BODY);

            assertThat(result).hasStatus(status);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
        }
    }

    @Nested
    @DisplayName("본문 검증")
    @WithMockUser(roles = "ADMIN")
    class BodyValidation {

        private static final String RATE = "{\"maxWeightGrams\": 500, \"fee\": {\"amount\": 1000, \"currency\": \"USD\"}}";

        private static String zones(String zones) {
            return "{\"baseFee\": {\"amount\": 500, \"currency\": \"USD\"}, \"zones\": " + zones + "}";
        }

        private static String zone(String name, String countryCodes, String rates) {
            return zones("[{\"name\": " + name + ", \"countryCodes\": " + countryCodes + ", \"rates\": " + rates + "}]");
        }

        @Test
        @DisplayName("baseFee 가 없으면 400 · DELIVERY_109")
        void baseFeeMissing() {
            assertBadRequest(put("{\"zones\": [{\"name\": \"가\", \"countryCodes\": [\"JP\"], \"rates\": [" + RATE + "]}]}"), "DELIVERY_109", "baseFee");
        }

        @Test
        @DisplayName("zones 가 없거나 비면 400 · DELIVERY_122")
        void zonesMissing() {
            assertBadRequest(put("{\"baseFee\": {\"amount\": 500, \"currency\": \"USD\"}}"), "DELIVERY_122", "zones");
            assertBadRequest(put(zones("[]")), "DELIVERY_122", "zones");
        }

        @Test
        @DisplayName("그룹이 51개면 400 · DELIVERY_123")
        void tooManyZones() {
            String many = IntStream.range(0, 51)
                .mapToObj(index -> "{\"name\": \"g%d\", \"countryCodes\": [\"JP\"], \"rates\": [%s]}".formatted(index, RATE))
                .collect(Collectors.joining(","));

            assertBadRequest(put(zones("[" + many + "]")), "DELIVERY_123", "zones");
        }

        @Test
        @DisplayName("그룹 원소가 null 이면 400 · DELIVERY_124")
        void nullZone() {
            assertBadRequest(put(zones("[null]")), "DELIVERY_124", "zones[0]");
        }

        @Test
        @DisplayName("그룹 이름이 비거나 51자면 400 · DELIVERY_125 · 126")
        void zoneName() {
            assertBadRequest(put(zone("\" \"", "[\"JP\"]", "[" + RATE + "]")), "DELIVERY_125", "zones[0].name");
            assertBadRequest(put(zone("\"" + "가".repeat(51) + "\"", "[\"JP\"]", "[" + RATE + "]")), "DELIVERY_126", "zones[0].name");
        }

        @Test
        @DisplayName("국가가 없거나 251개면 400 · DELIVERY_127 · 128")
        void countryCodesSize() {
            assertBadRequest(put(zone("\"가\"", "[]", "[" + RATE + "]")), "DELIVERY_127", "zones[0].countryCodes");
            String many = IntStream.range(0, 251).mapToObj(index -> "\"%c%c\"".formatted('A' + index / 26, 'A' + index % 26)).collect(Collectors.joining(","));
            assertBadRequest(put(zone("\"가\"", "[" + many + "]", "[" + RATE + "]")), "DELIVERY_128", "zones[0].countryCodes");
        }

        @Test
        @DisplayName("국가 원소가 null 이면 400 · DELIVERY_105, 형식이 틀리면 DELIVERY_106")
        void countryCodeElement() {
            assertBadRequest(put(zone("\"가\"", "[null]", "[" + RATE + "]")), "DELIVERY_105", "zones[0].countryCodes[0]");
            assertBadRequest(put(zone("\"가\"", "[\"jp\"]", "[" + RATE + "]")), "DELIVERY_106", "zones[0].countryCodes[0]");
        }

        @Test
        @DisplayName("구간이 없거나 101개면 400 · DELIVERY_129 · 130, 원소가 null 이면 DELIVERY_131")
        void ratesSize() {
            assertBadRequest(put(zone("\"가\"", "[\"JP\"]", "[]")), "DELIVERY_129", "zones[0].rates");
            String many = IntStream.range(1, 102)
                .mapToObj(index -> "{\"maxWeightGrams\": %d, \"fee\": {\"amount\": 1000, \"currency\": \"USD\"}}".formatted(index))
                .collect(Collectors.joining(","));
            assertBadRequest(put(zone("\"가\"", "[\"JP\"]", "[" + many + "]")), "DELIVERY_130", "zones[0].rates");
            assertBadRequest(put(zone("\"가\"", "[\"JP\"]", "[null]")), "DELIVERY_131", "zones[0].rates[0]");
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "{\"fee\": {\"amount\": 1000, \"currency\": \"USD\"}}                           | DELIVERY_132 | zones[0].rates[0].maxWeightGrams",
            "{\"maxWeightGrams\": 0, \"fee\": {\"amount\": 1000, \"currency\": \"USD\"}}     | DELIVERY_133 | zones[0].rates[0].maxWeightGrams",
            "{\"maxWeightGrams\": 1000001, \"fee\": {\"amount\": 1000, \"currency\": \"USD\"}} | DELIVERY_134 | zones[0].rates[0].maxWeightGrams",
            "{\"maxWeightGrams\": 500}                                                    | DELIVERY_135 | zones[0].rates[0].fee",
            "{\"maxWeightGrams\": 500, \"fee\": {\"amount\": -1, \"currency\": \"USD\"}}     | DELIVERY_103 | zones[0].rates[0].fee.amount"
        })
        @DisplayName("무게 구간 필드별 코드")
        void rateFields(String rate, String code, String field) {
            assertBadRequest(put(zone("\"가\"", "[\"JP\"]", "[" + rate + "]")), code, field);
        }

        @Test
        @DisplayName("상한 1,000,000g 은 받는다")
        void maxWeightAtLimit() {
            given(internationalShippingPolicyAdminUseCase.replaceInternationalPolicy(any())).willReturn(response());

            assertThat(put(zone("\"가\"", "[\"JP\"]", "[{\"maxWeightGrams\": 1000000, \"fee\": {\"amount\": 1000, \"currency\": \"USD\"}}]"))).hasStatusOk();
        }

        @Test
        @DisplayName("안내 문구가 1001자면 400 · DELIVERY_121")
        void customsNoticeTooLong() {
            String body = zone("\"가\"", "[\"JP\"]", "[" + RATE + "]").replace("\"zones\"", "\"customsNotice\": \"" + "가".repeat(1001) + "\", \"zones\"");

            assertBadRequest(put(body), "DELIVERY_121", "customsNotice");
        }

        @Test
        @DisplayName("도서산간 구간(remoteAreas)을 보내면 빈 배열이어도 400 · DELIVERY_136 — 조용히 버리지 않는다")
        void remoteAreasNotAllowed() {
            String body = zone("\"가\"", "[\"JP\"]", "[" + RATE + "]").replace("\"zones\"", "\"remoteAreas\": [], \"zones\"");

            assertBadRequest(put(body), "DELIVERY_136", "remoteAreas");
        }

        private void assertBadRequest(MvcTestResult result, String code, String field) {
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
            verifyNoInteractions(internationalShippingPolicyAdminUseCase);
        }
    }
}
