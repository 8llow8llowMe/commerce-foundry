package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.item.ShippingAddressItem;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressCreateResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressResponse;
import com.commercefoundry.domainlayer.shippingaddress.application.command.ShippingAddressCommand;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressErrorCode;
import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressException;
import com.commercefoundry.domainlayer.shippingaddress.application.port.in.ShippingAddressWebUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 배송지 API 의 HTTP 계약 — 봉투 모양 · 상태 코드 · 필드별 검증 코드(AUTH_120~129) · 404 · 201. 슬라이스라 보안 구성을 직접 import 한다.
 * 검증 오류는 회원 advice(서비스 공용 검증 처리)가, 배송지 도메인 오류는 배송지 advice 가 낸다 — 두 advice 가 함께 뜬 상태로 본다.
 */
@WebMvcTest(ShippingAddressWebController.class)
@Import({
    JwtVerificationPropertiesConfig.class,
    ResourceServerSecurityConfigurer.class
})
class ShippingAddressWebControllerTest {

    private static final String BASE = "/api/v1/members/me/shipping-addresses";
    private static final long MEMBER_ID = 4611686018427387904L;
    private static final long ADDRESS_ID = 4611686018427387905L;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ShippingAddressWebUseCase shippingAddressWebUseCase;

    @Nested
    @DisplayName("GET 목록 · 단건")
    class Get {

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이고 UseCase 를 부르지 않는다")
        void anonymous() {
            MvcTestResult result = mvc.get().uri(BASE).exchange();

            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_001");
            verifyNoInteractions(shippingAddressWebUseCase);
        }

        @Test
        @DisplayName("토큰의 memberId 로 목록을 낸다 — 아이디는 문자열, 기본 여부는 isDefault")
        void list() {
            given(shippingAddressWebUseCase.getMyAddresses(MEMBER_ID)).willReturn(ShippingAddressListResponse.builder()
                .addresses(List.of(ShippingAddressItem.builder()
                    .addressId("4611686018427387905").recipientName("홍길동").recipientPhone("01012345678").countryCode("KR").postalCode("06236")
                    .addressLine1("서울특별시 강남구 테헤란로 123").isDefault(true).build()))
                .maxCount(20)
                .build());

            MvcTestResult result = mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.addresses[0].addressId").asString().isEqualTo("4611686018427387905");
            assertThat(result).bodyJson().extractingPath("$.dataBody.addresses[0].isDefault").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.addresses[0].label").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.maxCount").isEqualTo(20);
        }

        @Test
        @DisplayName("없거나 남의 주소면 404 · AUTH_020 이다")
        void notFound() {
            given(shippingAddressWebUseCase.getMyAddress(MEMBER_ID, ADDRESS_ID))
                .willThrow(new ShippingAddressException(ShippingAddressErrorCode.NOT_FOUND_SHIPPING_ADDRESS));

            MvcTestResult result = mvc.get().uri(BASE + "/" + ADDRESS_ID).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_020");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("존재하지 않는 배송지입니다.");
        }

        @Test
        @DisplayName("주소 아이디가 숫자가 아니면 400 · AUTH_198 이다 (서비스 공용 검증 처리)")
        void typeMismatch() {
            MvcTestResult result = mvc.get().uri(BASE + "/abc").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_198");
        }
    }

    @Nested
    @DisplayName("POST")
    class Create {

        @Test
        @DisplayName("201 이고 addressId 는 문자열이다 — 본문은 그대로 커맨드가 되고 isDefault 는 따로 넘긴다")
        void created() {
            given(shippingAddressWebUseCase.create(anyLong(), any(), anyBoolean()))
                .willReturn(ShippingAddressCreateResponse.builder().addressId("4611686018427387905").build());

            MvcTestResult result = post(body("KR", "\"06236\"", "\"010-1234-5678\"", "null").replace("}", ",\"isDefault\":true}"));

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.dataBody.addressId").asString().isEqualTo("4611686018427387905");
            verify(shippingAddressWebUseCase).create(MEMBER_ID, new ShippingAddressCommand(
                "집", "홍길동", "010-1234-5678", "KR", "06236", "서울특별시 강남구 테헤란로 123", null, null, null
            ), true);
        }

        @Test
        @DisplayName("isDefault 가 없으면 false 로 넘긴다 — 해외 주소는 우편번호 없이 도시만 있으면 된다")
        void internationalWithoutPostalCode() {
            given(shippingAddressWebUseCase.create(anyLong(), any(), anyBoolean()))
                .willReturn(ShippingAddressCreateResponse.builder().addressId("1").build());

            assertThat(post(body("US", "null", "\"+1 415-555-0100\"", "\"San Francisco\""))).hasStatus(HttpStatus.CREATED);
            verify(shippingAddressWebUseCase).create(anyLong(), any(), eq(false));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "국가 없음                | ''  | '\"06236\"' | '\"01012345678\"' | null           | AUTH_124 | countryCode",
            "국가 소문자              | kr  | '\"06236\"' | '\"01012345678\"' | null           | AUTH_124 | countryCode",
            "국가 세 글자             | KOR | '\"06236\"' | '\"01012345678\"' | null           | AUTH_124 | countryCode",
            "국내 우편번호 없음       | KR  | null        | '\"01012345678\"' | null           | AUTH_125 | postalCode",
            "국내 우편번호 공백       | KR  | '\"  \"'    | '\"01012345678\"' | null           | AUTH_125 | postalCode",
            "국내 우편번호 4자리      | KR  | '\"1234\"'  | '\"01012345678\"' | null           | AUTH_126 | postalCode",
            "해외 우편번호 밑줄       | US  | '\"9_1\"'   | '\"+14155550100\"' | '\"SF\"'      | AUTH_126 | postalCode",
            "국내 연락처 1588         | KR  | '\"06236\"' | '\"1588-1234\"'   | null           | AUTH_123 | recipientPhone",
            "국내 연락처 국가번호     | KR  | '\"06236\"' | '\"+821012345678\"' | null         | AUTH_123 | recipientPhone",
            "해외 연락처 6자리        | US  | null        | '\"123456\"'      | '\"SF\"'       | AUTH_123 | recipientPhone",
            "연락처 없음              | KR  | '\"06236\"' | '\"  \"'          | null           | AUTH_122 | recipientPhone",
            "해외 도시 없음           | US  | null        | '\"+14155550100\"' | null          | AUTH_129 | city",
            "해외 도시 공백           | JP  | null        | '\"+81312345678\"' | '\"  \"'      | AUTH_129 | city"
        })
        @DisplayName("국가별 규칙 위반은 필드 이름 · 코드로 400 이고 UseCase 를 부르지 않는다")
        void regionErrors(String caseName, String country, String postalJson, String phoneJson, String cityJson, String code, String field) {
            assertValidationError(post(body(country, postalJson, phoneJson, cityJson)), code, field);
            verifyNoInteractions(shippingAddressWebUseCase);
        }

        @Test
        @DisplayName("필수 · 길이 위반 코드 — 별칭 120 · 이름 121 · 주소 필수 127 · 주소 칸 길이 128(필드는 fieldErrors 로)")
        void fieldErrors() {
            assertValidationError(post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("\"집\"", "\"" + "가".repeat(31) + "\"")),
                "AUTH_120", "label");
            assertValidationError(post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("\"홍길동\"", "\" \"")), "AUTH_121", "recipientName");
            assertValidationError(post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("\"홍길동\"", "\"" + "가".repeat(51) + "\"")),
                "AUTH_121", "recipientName");
            assertValidationError(post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("\"서울특별시 강남구 테헤란로 123\"", "\"\"")),
                "AUTH_127", "addressLine1");
            assertValidationError(post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("\"서울특별시 강남구 테헤란로 123\"", "\"" + "가".repeat(201) + "\"")),
                "AUTH_128", "addressLine1");
            assertValidationError(post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("}", ",\"addressLine2\":\"" + "가".repeat(201) + "\"}")),
                "AUTH_128", "addressLine2");
            assertValidationError(post(body("US", "null", "\"+14155550100\"", "\"" + "a".repeat(101) + "\"")), "AUTH_128", "city");
            assertValidationError(post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("}", ",\"stateProvince\":\"" + "a".repeat(101) + "\"}")),
                "AUTH_128", "stateProvince");
            verifyNoInteractions(shippingAddressWebUseCase);
        }

        @Test
        @DisplayName("전각 공백(U+3000)만 있는 필수 칸은 @NotBlank 를 지나도 400 이다 — 받는 사람 121 · 연락처 122 · 주소 127 (도메인 strip 기준)")
        void ideographicSpaceOnly() {
            String blank = "　　";
            String valid = body("KR", "\"06236\"", "\"01012345678\"", "null");

            assertValidationError(post(valid.replace("\"홍길동\"", "\"" + blank + "\"")), "AUTH_121", "recipientName");
            assertValidationError(post(body("KR", "\"06236\"", "\"" + blank + "\"", "null")), "AUTH_122", "recipientPhone");
            assertValidationError(post(valid.replace("\"서울특별시 강남구 테헤란로 123\"", "\"" + blank + "\"")), "AUTH_127", "addressLine1");
            assertValidationError(post(body("kr", "\"06236\"", "\"01012345678\"", "null").replace("\"홍길동\"", "\"" + blank + "\"")),
                "AUTH_121", "recipientName");
            MvcTestResult put = mvc.put().uri(BASE + "/" + ADDRESS_ID).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(valid.replace("\"홍길동\"", "\"" + blank + "\"")).exchange();
            assertValidationError(put, "AUTH_121", "recipientName");
            verifyNoInteractions(shippingAddressWebUseCase);
        }

        @Test
        @DisplayName("ASCII 공백만 있는 필수 칸은 @NotBlank 한 번만 거절한다 — 같은 오류가 두 번 나가지 않는다")
        void asciiBlankReportedOnce() {
            MvcTestResult result = post(body("KR", "\"06236\"", "\"01012345678\"", "null").replace("\"홍길동\"", "\"   \""));

            assertValidationError(result, "AUTH_121", "recipientName");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors.length()").isEqualTo(1);
        }

        @Test
        @DisplayName("빈 본문 · 깨진 JSON 은 AUTH_100 이다")
        void unreadable() {
            assertThat(post("{\"countryCode\":")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_100");
        }

        @Test
        @DisplayName("상한을 넘으면 409 · AUTH_021 이다")
        void limitExceeded() {
            given(shippingAddressWebUseCase.create(anyLong(), any(), anyBoolean()))
                .willThrow(new ShippingAddressException(ShippingAddressErrorCode.SHIPPING_ADDRESS_LIMIT_EXCEEDED, 20));

            MvcTestResult result = post(body("KR", "\"06236\"", "\"01012345678\"", "null"));

            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_021");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("배송지는 최대 20개까지 저장할 수 있습니다.");
        }

        @Test
        @DisplayName("토큰은 유효하지만 회원이 없으면 404 · AUTH_003 이다 (회원 advice)")
        void unknownMember() {
            given(shippingAddressWebUseCase.create(anyLong(), any(), anyBoolean())).willThrow(new MemberException(MemberErrorCode.NOT_FOUND_MEMBER));

            MvcTestResult result = post(body("KR", "\"06236\"", "\"01012345678\"", "null"));

            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_003");
        }

        private MvcTestResult post(String json) {
            return mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
        }
    }

    @Nested
    @DisplayName("PUT · DELETE · 기본 지정")
    class Change {

        @Test
        @DisplayName("전체 교체는 200 이고 바뀐 주소를 낸다 — 본문의 isDefault 는 무시한다")
        void update() {
            given(shippingAddressWebUseCase.update(anyLong(), anyLong(), any())).willReturn(ShippingAddressResponse.builder()
                .addressId("4611686018427387905").recipientName("홍길동").countryCode("KR").isDefault(false).build());

            MvcTestResult result = mvc.put().uri(BASE + "/" + ADDRESS_ID).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body("KR", "\"06236\"", "\"02-123-4567\"", "null").replace("}", ",\"isDefault\":true}"))
                .exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.addressId").asString().isEqualTo("4611686018427387905");
            verify(shippingAddressWebUseCase).update(MEMBER_ID, ADDRESS_ID, new ShippingAddressCommand(
                "집", "홍길동", "02-123-4567", "KR", "06236", "서울특별시 강남구 테헤란로 123", null, null, null
            ));
        }

        @Test
        @DisplayName("전체 교체도 같은 검증 코드다 — 국내 우편번호 없음은 AUTH_125")
        void updateValidation() {
            MvcTestResult result = mvc.put().uri(BASE + "/" + ADDRESS_ID).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body("KR", "null", "\"01012345678\"", "null")).exchange();

            assertValidationError(result, "AUTH_125", "postalCode");
            verifyNoInteractions(shippingAddressWebUseCase);
        }

        @Test
        @DisplayName("다른 요청이 먼저 고쳤으면 409 · AUTH_022 다")
        void conflict() {
            given(shippingAddressWebUseCase.update(anyLong(), anyLong(), any()))
                .willThrow(new ShippingAddressException(ShippingAddressErrorCode.SHIPPING_ADDRESS_UPDATE_CONFLICT));

            MvcTestResult result = mvc.put().uri(BASE + "/" + ADDRESS_ID).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body("KR", "\"06236\"", "\"01012345678\"", "null")).exchange();

            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_022");
        }

        @Test
        @DisplayName("삭제 · 기본 지정은 200 이고 dataBody 는 null 이다")
        void deleteAndDefault() {
            MvcTestResult deleted = mvc.delete().uri(BASE + "/" + ADDRESS_ID).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();
            MvcTestResult defaulted = mvc.put().uri(BASE + "/" + ADDRESS_ID + "/default").header(HttpHeaders.AUTHORIZATION, bearer()).exchange();

            assertThat(deleted).hasStatusOk();
            assertThat(deleted).bodyJson().extractingPath("$.dataBody").isNull();
            assertThat(defaulted).hasStatusOk();
            assertThat(defaulted).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            verify(shippingAddressWebUseCase).delete(MEMBER_ID, ADDRESS_ID);
            verify(shippingAddressWebUseCase).setDefault(MEMBER_ID, ADDRESS_ID);
        }

        @Test
        @DisplayName("남의 주소를 지우거나 기본으로 지정하면 404 · AUTH_020 이다")
        void notFound() {
            willThrow(new ShippingAddressException(ShippingAddressErrorCode.NOT_FOUND_SHIPPING_ADDRESS)).given(shippingAddressWebUseCase).delete(MEMBER_ID, ADDRESS_ID);
            willThrow(new ShippingAddressException(ShippingAddressErrorCode.NOT_FOUND_SHIPPING_ADDRESS)).given(shippingAddressWebUseCase).setDefault(MEMBER_ID, ADDRESS_ID);

            assertThat(mvc.delete().uri(BASE + "/" + ADDRESS_ID).header(HttpHeaders.AUTHORIZATION, bearer()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_020");
            assertThat(mvc.put().uri(BASE + "/" + ADDRESS_ID + "/default").header(HttpHeaders.AUTHORIZATION, bearer()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_020");
        }

        @Test
        @DisplayName("익명 삭제 · 기본 지정은 401 이다")
        void anonymous() {
            assertThat(mvc.delete().uri(BASE + "/" + ADDRESS_ID).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(mvc.put().uri(BASE + "/" + ADDRESS_ID + "/default").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(shippingAddressWebUseCase);
        }
    }

    /** 별칭 "집" · 받는 사람 "홍길동" · 주소 고정. 국가 · 우편번호 · 연락처 · 도시는 JSON 조각(null 포함)으로 받는다. */
    private static String body(String country, String postalJson, String phoneJson, String cityJson) {
        return """
            {"label":"집","recipientName":"홍길동","recipientPhone":%s,"countryCode":"%s","postalCode":%s,\
            "addressLine1":"서울특별시 강남구 테헤란로 123","city":%s}""".formatted(phoneJson, country, postalJson, cityJson);
    }

    private static void assertValidationError(MvcTestResult result, String code, String field) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
    }
}
