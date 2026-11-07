package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminPasswordResetPropertiesTest {
    @Test
    void enabledRequiresSmtpAndTrustedHttpsUrl() {
        assertThatThrownBy(() -> properties(true, "https://admin.example.com/reset", ""))
            .isInstanceOf(IllegalArgumentException.class);
        for (String url : new String[] {"http://admin.example.com/reset", "https://admin.example.com/reset#token=x",
            "https://admin.example.com/reset?next=x", "https://user@admin.example.com/reset", "${RESET_URL}"}) {
            assertThatThrownBy(() -> properties(true, url, "test-fixture-only")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(properties(true, "https://admin.example.com/reset", "test-fixture-only").tokenTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void disabledDoesNotRequireCredentialsAndNeverPrintsThem() {
        assertThat(properties(false, "", "sensitive-fixture").toString()).doesNotContain("sensitive-fixture");
        assertThat(new AdminPasswordResetProperties(false, null, null, 0, null, null, null, null).enabled()).isFalse();
        assertThat(properties(true, "https://admin.example.com/reset", "sensitive-fixture").toString())
            .isEqualTo("AdminPasswordResetProperties[enabled=true]");
    }

    @ParameterizedTest(name = "required field {0}: {1}")
    @MethodSource("missingFields")
    void enabledRejectsEveryMissingOrUnresolvedRequiredField(int field, String value) {
        String[] fields = {"https://admin.example.com/reset", "smtp.example.com", "fixture-user",
            "test-only-not-a-credential", "no-reply@example.com"};
        fields[field] = value;
        assertThatThrownBy(() -> new AdminPasswordResetProperties(true, fields[0], fields[1], 587,
            fields[2], fields[3], fields[4], null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("비밀번호 재설정 설정을 확인해 주세요").hasNoCause();
    }

    static Stream<Arguments> missingFields() {
        return Stream.of(0, 1, 2, 3, 4)
            .flatMap(field -> Stream.of(null, "", " \t", "${UNRESOLVED}").map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65536})
    void enabledRejectsInvalidPorts(int port) {
        assertThatThrownBy(() -> withPortAndTtl(port, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void acceptsPortBoundaries(int port) {
        assertThat(withPortAndTtl(port, null).smtpPort()).isEqualTo(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "user@example.com\r\nBcc: other@example.com", "user @example.com"})
    void enabledRejectsMalformedSender(String sender) {
        assertThatThrownBy(() -> new AdminPasswordResetProperties(true, "https://admin.example.com/reset", "smtp.example.com",
            587, "fixture-user", "test-only-not-a-credential", sender, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https:///reset", "https://", "not a URI", "/reset", "https://admin.example.com/reset?", "https://admin.example.com/reset#"})
    void enabledRejectsUntrustedOrMalformedUrls(String url) {
        assertThatThrownBy(() -> properties(true, url, "test-only-not-a-credential"))
            .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(longs = {60, 1800})
    void acceptsTtlBoundaries(long seconds) {
        assertThat(withPortAndTtl(587, Duration.ofSeconds(seconds)).tokenTtl()).isEqualTo(Duration.ofSeconds(seconds));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 59, 1801})
    void rejectsOutOfRangeTtlEvenWhenDisabled(long seconds) {
        for (boolean enabled : new boolean[] {false, true}) {
            assertThatThrownBy(() -> new AdminPasswordResetProperties(enabled, "https://admin.example.com/reset", "smtp.example.com",
                587, "fixture-user", "test-only-not-a-credential", "no-reply@example.com", Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static AdminPasswordResetProperties withPortAndTtl(int port, Duration ttl) {
        return new AdminPasswordResetProperties(true, "https://admin.example.com/reset", "smtp.example.com", port,
            "fixture-user", "test-only-not-a-credential", "no-reply@example.com", ttl);
    }

    private static AdminPasswordResetProperties properties(boolean enabled, String url, String password) {
        return new AdminPasswordResetProperties(enabled, url, "smtp.example.com", 587, "fixture-user", password,
            "no-reply@example.com", Duration.ofMinutes(15));
    }
}
