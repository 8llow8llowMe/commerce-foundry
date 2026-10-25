package com.commercefoundry.security.resourceserver;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorEnvelope;
import com.commercefoundry.security.internal.ServicePrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 거부 응답기의 로그를 본다 — 경로 마스킹, 서비스 간 인증 체인의 로그 수준(007 warn · 006 error · 허용 목록 거부 warn).
 */
@ExtendWith(OutputCaptureExtension.class)
class SecurityErrorResponderTest {

    private static final String ORDER_ID = "4611686018427387904";
    private static final String INTERNAL_PATH = "/internal/v1/orders/" + ORDER_ID + "/payment-completed";

    private final SecurityErrorEnvelope envelope = new SecurityErrorEnvelope(JsonMapper.builder().build());
    private final SecurityErrorResponder serviceResponder = SecurityErrorResponder.forServiceAuthentication(envelope);
    private final SecurityErrorResponder userResponder = new SecurityErrorResponder(envelope);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/internal/v1/orders/4611686018427387904 | /internal/v1/orders/…",
        "/internal/v1/orders/4611686018427387904/payment-completed | /internal/v1/orders/…",
        "/api/v1/admin/products/1 | /api/v1/admin/…",
        "/internal/v1/__security-smoke | /internal/v1/__security-smoke",
        "/api/v1/smoke | /api/v1/smoke",
        "/internal/v1/orders/ | /internal/v1/orders/",
        "/api/v1/admin;jsessionid=x/y | /api/v1/admin;…/…",
        "/ | /"
    })
    @DisplayName("로그 경로는 앞 세 구간만 남기고 나머지를 /… 로 가린다 — 경로의 주문번호 같은 식별자를 남기지 않는다")
    void masksPathAfterThreeSegments(String path, String expected) {
        assertThat(SecurityErrorResponder.maskPath(path)).isEqualTo(expected);
    }

    @Test
    @DisplayName("서비스 인증 실패는 401 · 007 이고 WARN 한 줄 — 경로의 식별자는 가린다")
    void serviceAuthenticationFailureLogsWarnWithMaskedPath(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        serviceResponder.commence(request(), response, new InsufficientAuthenticationException("Full authentication is required"));

        assertEnvelope(response, SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertThat(logLines(output, "service authentication rejected")).singleElement().asString()
            .contains("WARN", "code=SECURITY_007", "path=/internal/v1/orders/…").doesNotContain(ORDER_ID);
    }

    @Test
    @DisplayName("서비스 인증 체인의 검증 시스템 오류는 503 · 006 이고 ERROR 다 — 메모리 비밀만 쓰므로 서버 결함 신호다")
    void serviceVerificationFailureLogsError(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        serviceResponder.commence(request(), response, new AuthenticationServiceException("decoder failure"));

        assertEnvelope(response, SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
        assertThat(logLines(output, "service token verification failed")).singleElement().asString()
            .contains("ERROR", "code=SECURITY_006").doesNotContain(ORDER_ID);
    }

    @Test
    @DisplayName("사용자 체인의 검증 시스템 오류는 그대로 WARN 이다 — #4 의 검증 저장소 장애")
    void userVerificationFailureStaysWarn(CapturedOutput output) throws Exception {
        userResponder.commence(request(), new MockHttpServletResponse(), new AuthenticationServiceException("store down"));

        assertThat(logLines(output, "token verification unavailable")).singleElement().asString().contains("WARN").doesNotContain(ORDER_ID);
    }

    @Test
    @DisplayName("허용 목록 밖 서비스의 거부는 403 · 005 이고 WARN 에 호출 서비스 · jti · 가린 경로가 남는다")
    void serviceAccessDeniedLogsWarnWithCaller(CapturedOutput output) throws Exception {
        ServicePrincipal caller = new ServicePrincipal("delivery-service", "jti-1");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(caller, null, AuthorityUtils.createAuthorityList(caller.authority())));
        MockHttpServletResponse response = new MockHttpServletResponse();

        serviceResponder.handle(request(), response, new AccessDeniedException("denied"));

        assertEnvelope(response, SecurityErrorCode.FORBIDDEN);
        assertThat(logLines(output, "service access denied")).singleElement().asString()
            .contains("WARN", "callerService=delivery-service", "jti=jti-1", "path=/internal/v1/orders/…").doesNotContain(ORDER_ID);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", INTERNAL_PATH);
        request.setServletPath(INTERNAL_PATH);
        return request;
    }

    private static List<String> logLines(CapturedOutput output, String marker) {
        return output.getOut().lines().filter(line -> line.contains(marker)).toList();
    }
}
