package com.commercefoundry.security.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.error.SecurityErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증·인가 실패 응답이 {@code Response.fail} 봉투인지 단언한다 ({@code api-design-guide.md} §2-1, {@code security-guide.md} §4).
 * 바이트 비교가 아니라 JSON 트리로 타입 계약까지 본다 — {@code success} 는 boolean {@code false}, {@code resultMessage} 는 문자열,
 * {@code fieldErrors} · {@code dataBody} 는 {@code null}.
 */
public final class SecurityEnvelopeAssertions {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SecurityEnvelopeAssertions() {
    }

    /** 상태 · 코드 · 문구가 {@code errorCode} 와 같은 실패 봉투다. */
    public static void assertEnvelope(MockHttpServletResponse response, SecurityErrorCode errorCode) {
        assertEnvelope(response, errorCode.getHttpStatus().value(), errorCode.getCode(), errorCode.getMessage());
    }

    /** {@link SecurityErrorCode} 밖의 코드(서비스 간 인증 등)도 같은 모양으로 본다. */
    public static void assertEnvelope(MockHttpServletResponse response, int status, String resultCode, String resultMessage) {
        assertThat(response.getStatus()).as("status").isEqualTo(status);
        assertThat(response.getContentType()).as("content type").startsWith("application/json");
        assertThat(response.getContentAsByteArray()).as("본문").isNotEmpty();

        // get() 은 키가 없으면 null 이라 NPE 로 끝난다. path() 는 MissingNode 를 돌려 아래 단언이 어떤 키가 빠졌는지 알린다
        JsonNode root = JSON.readTree(response.getContentAsByteArray());
        JsonNode header = root.path("dataHeader");
        assertThat(header.isObject()).as("dataHeader 는 객체다").isTrue();
        assertThat(header.path("success").isBoolean()).as("dataHeader.success 는 boolean 이다").isTrue();
        assertThat(header.path("success").asBoolean()).as("dataHeader.success").isFalse();
        assertThat(header.path("resultCode").isString()).as("dataHeader.resultCode 는 문자열이다").isTrue();
        assertThat(header.path("resultCode").asString()).as("dataHeader.resultCode").isEqualTo(resultCode);
        assertThat(header.path("resultMessage").isString()).as("dataHeader.resultMessage 는 문자열이다").isTrue();
        assertThat(header.path("resultMessage").asString()).as("dataHeader.resultMessage").isEqualTo(resultMessage);
        assertThat(header.path("fieldErrors").isNull()).as("dataHeader.fieldErrors 는 null 이다(키는 있다)").isTrue();
        assertThat(root.path("dataBody").isNull()).as("dataBody 는 null 이다(키는 있다)").isTrue();
    }

    /** {@code WWW-Authenticate} 값. 403 · 503 처럼 헤더가 없어야 하면 {@code null} 을 넘긴다. */
    public static void assertWwwAuthenticate(MockHttpServletResponse response, String expected) {
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).as(HttpHeaders.WWW_AUTHENTICATE).isEqualTo(expected);
    }
}
