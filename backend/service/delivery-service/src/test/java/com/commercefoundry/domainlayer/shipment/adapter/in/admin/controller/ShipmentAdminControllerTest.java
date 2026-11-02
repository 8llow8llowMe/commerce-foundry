package com.commercefoundry.domainlayer.shipment.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentRegisterResultAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentStatusHistoryAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentAdminResponse;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentRegisterAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentStatusChangeCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import com.commercefoundry.domainlayer.shipment.application.model.ShipmentStatusFilter;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentAdminUseCase;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentChangedBy;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 송장 등록 API 의 HTTP 계약 — 권한 · 등록한 관리자(토큰의 memberId) 전달 · 본문 검증 코드 · 응답 모양 · 예외 핸들러 순서.
 * 등록이 {@code @AuthenticationPrincipal MemberPrincipal} 을 받으므로 테스트 키로 서명한 실제 access 토큰을 보낸다.
 */
@WebMvcTest(ShipmentAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class ShipmentAdminControllerTest {

    private static final String PATH = "/api/v1/admin/deliveries";
    private static final String VALID_BODY = """
        {"items": [
          {"orderId": "4611686018427387904", "carrier": "CJ_LOGISTICS", "trackingNumber": "123456789012"},
          {"orderId": "4611686018427387905", "carrier": "EMS", "trackingNumber": "EE123456789KR"}
        ]}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ShipmentAdminUseCase shipmentAdminUseCase;

    private MvcTestResult post(String body) {
        return post(body, MemberRole.ADMIN);
    }

    private MvcTestResult post(String body, MemberRole role) {
        return mvc.post().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private static String items(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> "{\"orderId\": \"" + i + "\", \"carrier\": \"HANJIN\", \"trackingNumber\": \"T" + i + "\"}")
            .collect(Collectors.joining(",", "{\"items\": [", "]}"));
    }

    private static void assertCode(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").asString().isEqualTo(code);
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(post(VALID_BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(shipmentAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 401 · SECURITY_001 이다")
        void anonymousIsUnauthorized() {
            MvcTestResult result = mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY).exchange();

            SecurityEnvelopeAssertions.assertEnvelope(result.getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(shipmentAdminUseCase);
        }
    }

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        @DisplayName("본문을 요청 순서 그대로 command 로 바꾸고, 등록한 관리자는 토큰의 memberId 다. 응답은 건수 · 건별 결과(아이디 문자열)다")
        void registers() {
            given(shipmentAdminUseCase.register(any())).willReturn(new ShipmentRegisterAdminResponse(1, 0, 1, List.of(
                ShipmentRegisterResultAdminItem.builder().orderId("4611686018427387904").deliveryId("4611686018427387906").result("REGISTERED").build(),
                ShipmentRegisterResultAdminItem.builder().orderId("4611686018427387905").result("FAILED").errorCode("DELIVERY_015")
                    .message("취소 요청 상태라 출고할 수 없습니다.").build())));

            MvcTestResult result = post(VALID_BODY);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.registeredCount").asNumber().isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.failedCount").asNumber().isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.results[0].deliveryId").asString().isEqualTo("4611686018427387906");
            assertThat(result).bodyJson().extractingPath("$.dataBody.results[1].deliveryId").isNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.results[1].errorCode").asString().isEqualTo("DELIVERY_015");

            ArgumentCaptor<ShipmentRegisterCommand> captor = ArgumentCaptor.forClass(ShipmentRegisterCommand.class);
            verify(shipmentAdminUseCase).register(captor.capture());
            assertThat(captor.getValue().adminMemberId()).isEqualTo(TestTokens.MEMBER_ID);
            assertThat(captor.getValue().items()).containsExactly(
                new ShipmentRegisterCommand.Item(4611686018427387904L, CarrierCode.CJ_LOGISTICS, "123456789012"),
                new ShipmentRegisterCommand.Item(4611686018427387905L, CarrierCode.EMS, "EE123456789KR"));
        }

        @Test
        @DisplayName("20건까지 받는다")
        void acceptsTwenty() {
            given(shipmentAdminUseCase.register(any())).willReturn(new ShipmentRegisterAdminResponse(0, 0, 0, List.of()));

            assertThat(post(items(20))).hasStatusOk();
        }
    }

    @Nested
    @DisplayName("본문 검증")
    class Validation {

        @ParameterizedTest(name = "{0}")
        @CsvSource(value = {
            "items 없음 | {} | DELIVERY_140",
            "items 빈 배열 | {\"items\": []} | DELIVERY_140",
            "null 원소 | {\"items\": [null]} | DELIVERY_141",
            "orderId 없음 | {\"items\": [{\"carrier\": \"DHL\", \"trackingNumber\": \"1\"}]} | DELIVERY_142",
            "orderId 숫자 아님 | {\"items\": [{\"orderId\": \"abc\", \"carrier\": \"DHL\", \"trackingNumber\": \"1\"}]} | DELIVERY_142",
            "orderId 0 | {\"items\": [{\"orderId\": \"0\", \"carrier\": \"DHL\", \"trackingNumber\": \"1\"}]} | DELIVERY_142",
            "orderId long 범위 밖 | {\"items\": [{\"orderId\": \"9999999999999999999\", \"carrier\": \"DHL\", \"trackingNumber\": \"1\"}]} | DELIVERY_142",
            "같은 주문 두 번 | {\"items\": [{\"orderId\": \"1\", \"carrier\": \"DHL\", \"trackingNumber\": \"1\"}, {\"orderId\": \"1\", \"carrier\": \"UPS\", \"trackingNumber\": \"2\"}]} | DELIVERY_143",
            "carrier 없음 | {\"items\": [{\"orderId\": \"1\", \"trackingNumber\": \"1\"}]} | DELIVERY_144",
            "송장 번호 없음 | {\"items\": [{\"orderId\": \"1\", \"carrier\": \"DHL\"}]} | DELIVERY_145",
            "송장 번호 공백 | {\"items\": [{\"orderId\": \"1\", \"carrier\": \"DHL\", \"trackingNumber\": \"12 34\"}]} | DELIVERY_145",
            "송장 번호 빈 값 | {\"items\": [{\"orderId\": \"1\", \"carrier\": \"DHL\", \"trackingNumber\": \"\"}]} | DELIVERY_145",
            "모르는 택배사 | {\"items\": [{\"orderId\": \"1\", \"carrier\": \"PIGEON\", \"trackingNumber\": \"1\"}]} | DELIVERY_100",
            "깨진 JSON | {\"items\": [ | DELIVERY_100"
        }, delimiter = '|')
        @DisplayName("400 과 필드별 검증 코드이고 유스케이스를 부르지 않는다")
        void rejects(String name, String body, String code) {
            assertCode(post(body), 400, code);
            verifyNoInteractions(shipmentAdminUseCase);
        }

        @Test
        @DisplayName("21건이면 DELIVERY_140 이다")
        void rejectsFiftyOne() {
            assertCode(post(items(21)), 400, "DELIVERY_140");
        }

        @Test
        @DisplayName("송장 번호 51자면 DELIVERY_145 다")
        void rejectsLongTrackingNumber() {
            assertCode(post("{\"items\": [{\"orderId\": \"1\", \"carrier\": \"DHL\", \"trackingNumber\": \"" + "1".repeat(51) + "\"}]}"), 400, "DELIVERY_145");
        }
    }

    @Nested
    @DisplayName("예외 핸들러")
    class Handler {

        /**
         * 서비스 공용 {@code DeliveryExceptionHandler}(basePackages domainlayer)가 먼저 골라 DELIVERY_100 · 500 폴백이 나지 않고, 송장 핸들러({@code @Order})가
         * 송장 코드 · 상태 그대로 봉투를 만든다.
         */
        @ParameterizedTest
        @EnumSource(ShipmentErrorCode.class)
        @DisplayName("송장 도메인 예외는 그 코드 · HTTP 상태 · 메시지로 나간다")
        void shipmentExceptionKeepsCode(ShipmentErrorCode errorCode) {
            ShipmentException exception = errorCode == ShipmentErrorCode.ORDER_NOT_SHIPPABLE
                ? new ShipmentException(errorCode, "취소 요청") : new ShipmentException(errorCode);
            given(shipmentAdminUseCase.register(any())).willThrow(exception);

            MvcTestResult result = post(VALID_BODY);

            assertCode(result, errorCode.getHttpStatus().value(), errorCode.getCode());
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultMessage").asString().isEqualTo(exception.getMessage());
        }
    }

    private static final String DELIVERY_ID = "4611686018427387906";

    private static ShipmentAdminResponse detail(String status) {
        return ShipmentAdminResponse.builder()
            .deliveryId(DELIVERY_ID).orderId("4611686018427387904")
            .carrier(CarrierCode.CJ_LOGISTICS.toMetadata()).trackingNumber("123456789012")
            .status(ShipmentStatus.valueOf(status).toMetadata()).orderSynced(true).completionPending(false)
            .shippedAt(Instant.parse("2026-11-02T09:30:00.123456Z"))
            .histories(List.of(ShipmentStatusHistoryAdminItem.builder()
                .toStatus(ShipmentStatus.SHIPPED.toMetadata()).changedBy(ShipmentChangedBy.ADMIN.toMetadata()).changedById("9")
                .changedAt(Instant.parse("2026-11-02T09:30:00Z")).build()))
            .build();
    }

    private MvcTestResult patch(String body, MemberRole role) {
        return mvc.patch().uri(PATH + "/" + DELIVERY_ID + "/status").header(HttpHeaders.AUTHORIZATION, bearer(role))
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult get(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    @Nested
    @DisplayName("배송 상태 변경 (#18 PR-B2)")
    class ChangeStatus {

        @Test
        @DisplayName("경로 아이디 · 상태 · 사유와 토큰의 memberId 를 command 로 넘기고, 바뀐 상세(아이디 문자열 · 코드 값 · 이력)로 답한다")
        void changes() {
            given(shipmentAdminUseCase.changeStatus(any())).willReturn(detail("DELIVERED"));

            MvcTestResult result = patch("{\"status\": \"DELIVERED\", \"reason\": \"수령 확인\"}", MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.deliveryId").asString().isEqualTo(DELIVERY_ID);
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").asString().isEqualTo("DELIVERED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.carrier.name").asString().isEqualTo("CJ대한통운");
            assertThat(result).bodyJson().extractingPath("$.dataBody.histories[0].changedBy.code").asString().isEqualTo("ADMIN");
            assertThat(result).bodyJson().extractingPath("$.dataBody.histories[0].fromStatus").isNull();
            ArgumentCaptor<ShipmentStatusChangeCommand> captor = ArgumentCaptor.forClass(ShipmentStatusChangeCommand.class);
            verify(shipmentAdminUseCase).changeStatus(captor.capture());
            assertThat(captor.getValue()).isEqualTo(new ShipmentStatusChangeCommand(Long.parseLong(DELIVERY_ID), ShipmentStatus.DELIVERED, "수령 확인", TestTokens.MEMBER_ID));
        }

        @Test
        @DisplayName("사유는 없어도 되고 200자까지 받는다")
        void reasonOptional() {
            given(shipmentAdminUseCase.changeStatus(any())).willReturn(detail("IN_TRANSIT"));

            assertThat(patch("{\"status\": \"IN_TRANSIT\"}", MemberRole.ADMIN)).hasStatusOk();
            assertThat(patch("{\"status\": \"IN_TRANSIT\", \"reason\": \"" + "가".repeat(200) + "\"}", MemberRole.ADMIN)).hasStatusOk();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(value = {
            "status 없음 | {} | DELIVERY_146",
            "출고 완료로 | {\"status\": \"SHIPPED\"} | DELIVERY_146",
            "모르는 상태 | {\"status\": \"LOST\"} | DELIVERY_100",
            "사유 제어 문자 | {\"status\": \"DELIVERED\", \"reason\": \"a\\nb\"} | DELIVERY_148",
            "깨진 JSON | {\"status\": | DELIVERY_100"
        }, delimiter = '|')
        @DisplayName("본문 오류는 400 과 필드별 코드이고 유스케이스를 부르지 않는다")
        void rejects(String name, String body, String code) {
            assertCode(patch(body, MemberRole.ADMIN), 400, code);
            verifyNoInteractions(shipmentAdminUseCase);
        }

        @Test
        @DisplayName("사유 201자는 DELIVERY_147 이다")
        void rejectsLongReason() {
            assertCode(patch("{\"status\": \"DELIVERED\", \"reason\": \"" + "가".repeat(201) + "\"}", MemberRole.ADMIN), 400, "DELIVERY_147");
        }

        @Test
        @DisplayName("경로 아이디가 숫자가 아니면 400 · DELIVERY_198 이다")
        void rejectsNonNumericId() {
            MvcTestResult result = mvc.patch().uri(PATH + "/abc/status").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"DELIVERED\"}").exchange();

            assertCode(result, 400, "DELIVERY_198");
        }

        @Test
        @DisplayName("업무 오류는 송장 코드 · 상태로 나간다 — 예: 출고 미반영 409 · DELIVERY_025")
        void domainError() {
            given(shipmentAdminUseCase.changeStatus(any())).willThrow(new ShipmentException(ShipmentErrorCode.SHIPMENT_NOT_SYNCED));

            assertCode(patch("{\"status\": \"DELIVERED\"}", MemberRole.ADMIN), 409, "DELIVERY_025");
        }

        @Test
        @DisplayName("CUSTOMER 면 403 · SECURITY_005 이다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(patch("{\"status\": \"DELIVERED\"}", MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(shipmentAdminUseCase);
        }
    }

    @Nested
    @DisplayName("상세 · 목록 (#18 PR-B2)")
    class Query {

        @Test
        @DisplayName("상세는 경로 아이디로 읽고, 없으면 404 · DELIVERY_023 이다")
        void detailById() {
            given(shipmentAdminUseCase.getDetail(Long.parseLong(DELIVERY_ID))).willReturn(detail("SHIPPED"));
            given(shipmentAdminUseCase.getDetail(1L)).willThrow(new ShipmentException(ShipmentErrorCode.SHIPMENT_NOT_FOUND));

            MvcTestResult found = get(PATH + "/" + DELIVERY_ID, MemberRole.ADMIN);

            assertThat(found).hasStatusOk();
            assertThat(found).bodyJson().extractingPath("$.dataBody.orderId").asString().isEqualTo("4611686018427387904");
            assertCode(get(PATH + "/1", MemberRole.ADMIN), 404, "DELIVERY_023");
        }

        @Test
        @DisplayName("목록은 조건 · 페이지를 그대로 넘기고 PageResponse 로 답한다. 기본은 조건 없음 · page 0 · size 20 이다")
        void search() {
            given(shipmentAdminUseCase.search(any(), any(), any(), anyInt(), anyInt())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

            assertThat(get(PATH + "?orderId=12&status=DELIVERED&synced=false&page=1&size=100", MemberRole.ADMIN)).hasStatusOk();
            assertThat(get(PATH, MemberRole.ADMIN)).bodyJson().extractingPath("$.dataBody.totalElements").asNumber().isEqualTo(0);

            verify(shipmentAdminUseCase).search(12L, ShipmentStatusFilter.DELIVERED, false, 1, 100);
            verify(shipmentAdminUseCase).search(null, null, null, 0, 20);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(value = {
            "orderId 0 | ?orderId=0 | DELIVERY_142",
            "orderId 숫자 아님 | ?orderId=abc | DELIVERY_198",
            "모르는 상태 | ?status=LOST | DELIVERY_198",
            "synced 형식 | ?synced=maybe | DELIVERY_198",
            "page 음수 | ?page=-1 | DELIVERY_137",
            "size 0 | ?size=0 | DELIVERY_149",
            "size 101 | ?size=101 | DELIVERY_149"
        }, delimiter = '|')
        @DisplayName("쿼리 오류는 400 과 코드이고 유스케이스를 부르지 않는다")
        void rejects(String name, String query, String code) {
            assertCode(get(PATH + query, MemberRole.ADMIN), 400, code);
            verifyNoInteractions(shipmentAdminUseCase);
        }

        @Test
        @DisplayName("CUSTOMER 의 목록 · 상세는 403 · SECURITY_005 이다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(get(PATH, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(get(PATH + "/" + DELIVERY_ID, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(shipmentAdminUseCase);
        }
    }
}
