package com.commercefoundry.domainlayer.order.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderStatusBulkChangeResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.model.OrderStatusFilter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderAdminUseCase;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 어드민 주문 관리 API 의 HTTP 계약 (#17) — 어드민만 · 관리자 아이디는 토큰에서 · 파라미터 · 본문 검증. 테스트 값은 모두 가짜다. */
@WebMvcTest(OrderAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class OrderAdminControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderAdminUseCase orderAdminUseCase;

    /** 실패 봉투 — 고객 주문 API 테스트({@code OrderQuoteWebControllerTest})와 같은 단언. */
    private static void assertFailure(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private MvcTestResult get(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult patch(String uri, String body) {
        return mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
        "GET   | /api/v1/admin/orders",
        "GET   | /api/v1/admin/orders/1",
        "PATCH | /api/v1/admin/orders/1/status",
        "POST  | /api/v1/admin/orders/status-changes"
    })
    @DisplayName("네 경로 모두 고객 토큰은 403 SECURITY_005, 토큰 없으면 401 SECURITY_001 — 유스케이스에 닿지 않는다")
    void adminOnly(String method, String uri) {
        String body = uri.endsWith("status-changes") ? "{\"orderIds\":[\"1\"],\"status\":\"PREPARING\"}" : "{\"status\":\"PREPARING\"}";
        assertFailure(request(method.strip(), uri.strip(), body, bearer(MemberRole.CUSTOMER)), HttpStatus.FORBIDDEN, "SECURITY_005");
        assertFailure(request(method.strip(), uri.strip(), body, null), HttpStatus.UNAUTHORIZED, "SECURITY_001");
        verifyNoInteractions(orderAdminUseCase);
    }

    private MvcTestResult request(String method, String uri, String body, String authorization) {
        var builder = switch (method) {
            case "GET" -> mvc.get().uri(uri);
            case "PATCH" -> mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
            default -> mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
        };
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return builder.exchange();
    }

    @Test
    @DisplayName("목록 — 상태 · 기간 · 페이지와 토큰의 관리자 아이디를 넘긴다(열람 감사)")
    void listPassesFilters() {
        given(orderAdminUseCase.getOrders(any(), anyLong())).willReturn(new PageResponse<>(List.of(), 1, 50, 0, 0));

        assertThat(get("/api/v1/admin/orders?status=PAID&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z&page=1&size=50", MemberRole.ADMIN))
            .hasStatus(HttpStatus.OK);

        ArgumentCaptor<OrderAdminSearchCommand> command = ArgumentCaptor.forClass(OrderAdminSearchCommand.class);
        verify(orderAdminUseCase).getOrders(command.capture(), eq(TestTokens.MEMBER_ID));
        assertThat(command.getValue()).isEqualTo(new OrderAdminSearchCommand(OrderStatusFilter.PAID, Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-11-01T00:00:00Z"), 1, 50));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "페이지 음수   | /api/v1/admin/orders?page=-1                                              | ORDER_144",
        "크기 101      | /api/v1/admin/orders?size=101                                             | ORDER_145",
        "기간 거꾸로   | /api/v1/admin/orders?from=2026-11-01T00:00:00Z&to=2026-10-01T00:00:00Z     | ORDER_146",
        "모르는 상태   | /api/v1/admin/orders?status=WHATEVER                                      | ORDER_198",
        "주문 아이디 0 | /api/v1/admin/orders/0                                                    | ORDER_141"
    })
    void queryValidation(String caseName, String uri, String code) {
        assertFailure(get(uri.strip(), MemberRole.ADMIN), HttpStatus.BAD_REQUEST, code.strip());
    }

    @Test
    @DisplayName("상태 변경 — 바꾼 관리자는 토큰의 회원 아이디이고, 사유 앞뒤 공백은 자른다")
    void changeStatusUsesTokenMember() {
        given(orderAdminUseCase.changeStatus(eq(9L), any(), anyLong())).willReturn(OrderAdminResponse.builder().orderId("9").build());

        assertThat(patch("/api/v1/admin/orders/9/status", "{\"status\":\"PREPARING\",\"reason\":\" 포장 시작 \"}")).hasStatus(HttpStatus.OK);

        verify(orderAdminUseCase).changeStatus(9L, new OrderStatusChangeCommand(OrderStatus.PREPARING, "포장 시작"), TestTokens.MEMBER_ID);
    }

    @Test
    @DisplayName("상태 변경 거절은 업무 오류 코드 그대로 409 다")
    void changeStatusConflict() {
        given(orderAdminUseCase.changeStatus(anyLong(), any(), anyLong()))
            .willThrow(new OrderException(OrderErrorCode.ADMIN_STATUS_CHANGE_NOT_ALLOWED, "결제 완료"));

        assertFailure(patch("/api/v1/admin/orders/9/status", "{\"status\":\"PAID\"}"), HttpStatus.CONFLICT, "ORDER_023");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "상태 없음       | /api/v1/admin/orders/9/status    | {}                                                                       | ORDER_147",
        "사유 줄바꿈     | /api/v1/admin/orders/9/status    | {\"status\":\"PREPARING\",\"reason\":\"a\\nb\"}                          | ORDER_151",
        "일괄 빈 목록    | /api/v1/admin/orders/status-changes | {\"orderIds\":[],\"status\":\"PREPARING\"}                            | ORDER_149",
        "일괄 중복       | /api/v1/admin/orders/status-changes | {\"orderIds\":[\"1\",\"1\"],\"status\":\"PREPARING\"}                 | ORDER_150",
        "일괄 아이디 형식 | /api/v1/admin/orders/status-changes | {\"orderIds\":[\"abc\"],\"status\":\"PREPARING\"}                     | ORDER_152",
        "일괄 아이디 범위 | /api/v1/admin/orders/status-changes | {\"orderIds\":[\"9999999999999999999\"],\"status\":\"PREPARING\"}     | ORDER_152",
        "일괄 배송 완료  | /api/v1/admin/orders/status-changes | {\"orderIds\":[\"1\"],\"status\":\"DELIVERED\",\"reason\":\"확인\"}     | ORDER_153",
        "사유 깨진 문자  | /api/v1/admin/orders/9/status    | {\"status\":\"PREPARING\",\"reason\":\"\\uD800\"}                       | ORDER_151"
    })
    void bodyValidation(String caseName, String uri, String body, String code) {
        String path = uri.strip();
        MvcTestResult result = path.endsWith("/status") ? patch(path, body.strip()) : post(path, body.strip());
        assertFailure(result, HttpStatus.BAD_REQUEST, code.strip());
        verifyNoInteractions(orderAdminUseCase);
    }

    @Test
    @DisplayName("일괄 변경 — 문자열 아이디를 요청 순서대로 넘기고 관리자는 토큰에서 온다")
    void bulkChange() {
        given(orderAdminUseCase.changeStatuses(any(), any(), anyLong())).willReturn(new OrderStatusBulkChangeResponse(2, 0, 0, List.of()));

        assertThat(post("/api/v1/admin/orders/status-changes", "{\"orderIds\":[\"4611686018427387905\",\"12\"],\"status\":\"PREPARING\"}"))
            .hasStatus(HttpStatus.OK);

        verify(orderAdminUseCase).changeStatuses(List.of(4611686018427387905L, 12L), new OrderStatusChangeCommand(OrderStatus.PREPARING, null), TestTokens.MEMBER_ID);
    }
}
