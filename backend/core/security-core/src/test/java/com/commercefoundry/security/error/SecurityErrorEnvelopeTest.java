package com.commercefoundry.security.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SecurityErrorEnvelopeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SecurityErrorEnvelope envelope = new SecurityErrorEnvelope(JSON);

    @ParameterizedTest
    @EnumSource(SecurityErrorCode.class)
    @DisplayName("본문은 Response.fail 봉투다 — resultMessage 는 문자열, fieldErrors · dataBody 는 null")
    void bodyIsFailEnvelope(SecurityErrorCode errorCode) {
        JsonNode root = JSON.readTree(envelope.body(errorCode));
        JsonNode header = root.get("dataHeader");

        assertThat(header.get("success").asBoolean()).isFalse();
        assertThat(header.get("resultCode").asString()).isEqualTo(errorCode.getCode());
        assertThat(header.get("resultMessage").isString()).isTrue();
        assertThat(header.get("resultMessage").asString()).isEqualTo(errorCode.getMessage());
        assertThat(header.get("fieldErrors").isNull()).isTrue();
        assertThat(root.get("dataBody").isNull()).isTrue();
    }

    @Test
    @DisplayName("401 만 WWW-Authenticate 를 싣는다 — 토큰 없음 · 서비스 인증 실패는 Bearer, 만료는 설명까지, 그 밖의 거부는 invalid_token")
    void wwwAuthenticateFollowsRfc6750() {
        assertThat(SecurityErrorEnvelope.wwwAuthenticate(SecurityErrorCode.UNAUTHORIZED)).hasValue("Bearer");
        assertThat(SecurityErrorEnvelope.wwwAuthenticate(SecurityErrorCode.SERVICE_UNAUTHORIZED)).hasValue("Bearer");
        assertThat(SecurityErrorEnvelope.wwwAuthenticate(SecurityErrorCode.TOKEN_EXPIRED))
            .hasValue("Bearer error=\"invalid_token\", error_description=\"The access token expired\"");
        assertThat(SecurityErrorEnvelope.wwwAuthenticate(SecurityErrorCode.TOKEN_INVALID)).hasValue("Bearer error=\"invalid_token\"");
        assertThat(SecurityErrorEnvelope.wwwAuthenticate(SecurityErrorCode.TOKEN_REVOKED)).hasValue("Bearer error=\"invalid_token\"");
        assertThat(SecurityErrorEnvelope.wwwAuthenticate(SecurityErrorCode.FORBIDDEN)).isEmpty();
        assertThat(SecurityErrorEnvelope.wwwAuthenticate(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE)).isEmpty();
    }

    @Test
    @DisplayName("코드는 SECURITY_001~007 로 겹치지 않고, 401 · 403 · 503 만 쓴다")
    void codesAreUniqueAndStatusesAreFixed() {
        assertThat(Arrays.stream(SecurityErrorCode.values()).map(SecurityErrorCode::getCode))
            .containsExactly("SECURITY_001", "SECURITY_002", "SECURITY_003", "SECURITY_004", "SECURITY_005", "SECURITY_006", "SECURITY_007");
        assertThat(Arrays.stream(SecurityErrorCode.values()).map(code -> code.getHttpStatus().value()))
            .containsExactly(401, 401, 401, 401, 403, 503, 401);
    }
}
