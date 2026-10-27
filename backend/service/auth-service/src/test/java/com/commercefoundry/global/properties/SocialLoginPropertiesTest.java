package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;

class SocialLoginPropertiesTest {
    @Test
    void disabledDefaultsBindWithoutCredentials() {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of("auth.social.enabled", "false")))
            .bind("auth.social", Bindable.of(SocialLoginProperties.class)).get();
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.stateTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.kakao().enabled()).isFalse();
    }

    @Test
    void enabledRequiresCredentialsAndSafeConfiguredRedirect() {
        assertThatThrownBy(() -> properties(true, new SocialLoginProperties.Provider(true, "", "", "")))
            .isInstanceOf(IllegalArgumentException.class);
        for (String redirect : new String[]{"http://evil.example/callback", "https://user:secret@example.com/cb",
            "https://example.com/cb?next=evil", "https://example.com/cb#fragment", "${KAKAO_REDIRECT_URI}"}) {
            assertThatThrownBy(() -> properties(true, new SocialLoginProperties.Provider(true, "client", "secret", redirect)))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(properties(true, new SocialLoginProperties.Provider(true, "client", "secret", "http://localhost:3000/callback")).enabled()).isTrue();
    }

    @Test
    void ttlAndTimeoutBoundsFailClosed() {
        assertThatThrownBy(() -> new SocialLoginProperties(false, Duration.ofMinutes(11), Duration.ofSeconds(2), Duration.ofSeconds(3), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SocialLoginProperties(false, Duration.ofMinutes(5), Duration.ZERO, Duration.ofSeconds(3), null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private SocialLoginProperties properties(boolean enabled, SocialLoginProperties.Provider kakao) {
        return new SocialLoginProperties(enabled, Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(3), kakao,
            new SocialLoginProperties.Provider(false, null, null, null));
    }
}