package com.commercefoundry.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.properties.AiProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiProviderBoundaryTest {

    private final AiProvider provider = mock(AiProvider.class);

    @Test
    void disabledNeverCreatesProviderEvenWithoutKeyOrMetering() {
        AiClient client = AiClients.create(properties(false, null, false), List.of(), provider);

        assertThatThrownBy(() -> client.complete(AiRequest.of("test", null, "prompt")))
            .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.reason()).isEqualTo(AiException.Reason.DISABLED));
        verifyNoInteractions(provider);
    }

    @Test
    void invalidConfigurationCannotReachProviderOrDiscloseKey() {
        assertThatThrownBy(() -> AiClients.create(properties(true, "${PRIVATE_KEY_PROBE}", true), List.of(), provider))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ai.api-key").hasMessageNotContaining("PRIVATE_KEY_PROBE");
        verifyNoInteractions(provider);
    }

    @Test
    void missingMeteringCannotReachProvider() {
        assertThatThrownBy(() -> AiClients.create(properties(true, "test-key", false), List.of(), provider))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ai.allow-unmetered");
        verifyNoInteractions(provider);
    }

    @Test
    void multipleInterceptorsCannotReachProvider() {
        List<AiCallInterceptor> meters = List.of(mock(AiCallInterceptor.class), mock(AiCallInterceptor.class));
        assertThatThrownBy(() -> AiClients.create(properties(true, "test-key", true), meters, provider))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(provider);
    }

    @Test
    void passesValidatedConfigurationMaskerAndSameMeterWithoutWrappingClient() {
        AiProperties settings = properties(true, "test-key", false);
        AiCallInterceptor meter = mock(AiCallInterceptor.class);
        AiClient expected = mock(AiClient.class);
        when(provider.create(same(settings), any(PiiMasker.class), same(meter))).thenAnswer(invocation -> {
            PiiMasker masker = invocation.getArgument(1);
            assertThat(masker.mask("probe@example.test", List.of())).doesNotContain("probe@example.test");
            return expected;
        });

        assertThat(AiClients.create(settings, List.of(meter), provider)).isSameAs(expected);
        verify(provider).create(same(settings), any(PiiMasker.class), same(meter));
        verifyNoInteractions(expected, meter);
    }

    @Test
    void explicitlyUnmeteredProviderReceivesNullInterceptor() {
        when(provider.create(any(), any(), isNull())).thenReturn(mock(AiClient.class));
        AiClients.create(properties(true, "test-key", true), List.of(), provider);
        verify(provider).create(any(), any(PiiMasker.class), isNull());
    }

    @Test
    void creationFailurePropagatesWithoutFallback() {
        RuntimeException failure = new IllegalStateException("provider construction failed");
        when(provider.create(any(), any(), isNull())).thenThrow(failure);

        assertThatThrownBy(() -> AiClients.create(properties(true, "test-key", true), List.of(), provider)).isSameAs(failure);
        verify(provider).create(any(), any(PiiMasker.class), isNull());
    }

    private static AiProperties properties(boolean enabled, String key, boolean allowUnmetered) {
        return new AiProperties(enabled, key, null, null, null, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
            0, 1, Duration.ofSeconds(1), false, Map.of(), null, null, false, allowUnmetered);
    }
}
