package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

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
    }

    private static AdminPasswordResetProperties properties(boolean enabled, String url, String password) {
        return new AdminPasswordResetProperties(enabled, url, "smtp.example.com", 587, "fixture-user", password,
            "no-reply@example.com", Duration.ofMinutes(15));
    }
}
