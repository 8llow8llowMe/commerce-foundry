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

        JsonNode root = JSON.readTree(response.getContentAsByteArray());
        JsonNode header = root.get("dataHeader");
        assertThat(header.get("success").isBoolean()).isTrue();
        assertThat(header.get("success").asBoolean()).isFalse();
        assertThat(header.get("resultCode").asString()).isEqualTo(resultCode);
        assertThat(header.get("resultMessage").isString()).isTrue();
        assertThat(header.get("resultMessage").asString()).isEqualTo(resultMessage);
        assertThat(header.get("fieldErrors").isNull()).isTrue();
        assertThat(root.get("dataBody").isNull()).isTrue();
    }

    /** {@code WWW-Authenticate} 값. 403 · 503 처럼 헤더가 없어야 하면 {@code null} 을 넘긴다. */
    public static void assertWwwAuthenticate(MockHttpServletResponse response, String expected) {
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).as(HttpHeaders.WWW_AUTHENTICATE).isEqualTo(expected);
    }
}
