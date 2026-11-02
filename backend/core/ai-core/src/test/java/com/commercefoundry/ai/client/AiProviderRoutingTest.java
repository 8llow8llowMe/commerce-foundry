package com.commercefoundry.ai.client;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.ai.config.AiPropertiesConfig;
import com.commercefoundry.ai.properties.AiProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class AiProviderRoutingTest {
    @Test
    void explicitEnvironmentNamesBindThroughDeploymentPlaceholders() {
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment
               .getPropertySources()
               .addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource(
                                "probe-env", Map.of("AI_PROVIDER", "openai", "AI_OPENAI_API_KEY", "env-probe", "AI_OPENAI_MODEL", "env-model")));
        new ApplicationContextRunner()
               .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(environment.getPropertySources().get("probe-env")))
               .withUserConfiguration(AiPropertiesConfig.class)
               .withPropertyValues(
                        "ai.provider=${AI_PROVIDER:anthropic}",
                        "ai.openai.api-key=${AI_OPENAI_API_KEY:}",
                        "ai.openai.model=${AI_OPENAI_MODEL:}")
               .run(context -> {
                            var properties = context.getBean(AiProperties.class);
                            assertThat(properties.provider()).isEqualTo("openai");
                            assertThat(properties.openai().apiKey()).isEqualTo("env-probe");
                            assertThat(properties.openai().model()).isEqualTo("env-model");
                        });
    }

    @Test

    void bindsProvidersAndUsesSelectedDefaultModel() {
        new ApplicationContextRunner()
               .withUserConfiguration(AiPropertiesConfig.class)
               .withPropertyValues(
                        "ai.provider=openai",
                        "ai.openai.api-key=probe-key",
                        "ai.openai.model=selected-model",
                        "ai.features.chat.provider=anthropic",
                        "ai.features.chat.model=feature-model", "ai.features.chat.prompt-version=3", "ai.features.other.max-tokens=64").run(context -> {
                            assertThat(context).hasNotFailed();
                            var properties = context.getBean(AiProperties.class);
                            assertThat(properties.providerFor("other")).isEqualTo("openai");
                            assertThat(properties.resolve("other").model()).isEqualTo("selected-model");
                            assertThat(properties.providerFor("chat")).isEqualTo("anthropic");
                            assertThat(properties.resolve("chat").model()).isEqualTo("feature-model");
                            assertThat(properties.features().get("chat").promptVersion()).isEqualTo(3);
                            assertThat(properties.toString()).doesNotContain("probe-key");
                        });
    }

    @Test

    void invalidExplicitProviderNeverSilentlyInherits() {
        new ApplicationContextRunner()
               .withUserConfiguration(AiPropertiesConfig.class)
               .withPropertyValues(
                        "ai.enabled=true", "ai.features.chat.provider=${MISSING_PROVIDER}")
               .run(context -> assertThat(context.getBean(AiProperties.class).violations()).anyMatch(problem -> problem.property().endsWith(".provider")));
    }

    @Test

    void routerDispatchesAllFourMethodsAndClosesEveryClient() {
        AtomicInteger left = new AtomicInteger(), right = new AtomicInteger(), closed = new AtomicInteger();
        AiProperties properties =
                new AiProperties(
                        false,
                        null,
                        null,
                        null, null, null, null, null, null, null, null, null, Map.of("other", new AiProperties.Feature(null, null, null, null, "openai")), null, null, null, null);
        RoutedAiClient router = new RoutedAiClient(properties, Map.of("anthropic", new Probe(left, closed), "openai", new Probe(right, closed)));
        router.complete(AiRequest.of("other", null, "prompt"));
        router.complete(AiRequest.of("other", null, "prompt"), String.class, value -> List.of());
        router.converse(AiConversationRequest.of("other", null, List.of(AiMessage.user("prompt"))));
        router.converseStreaming(AiConversationRequest.of("other", null, List.of(AiMessage.user("prompt"))), delta -> true);
        router.complete(AiRequest.of("default", null, "prompt"));
        assertThat(right).hasValue(4);
        assertThat(left).hasValue(1);
        router.close();
        assertThat(closed).hasValue(2);
    }

    static class Probe implements AiClient, AutoCloseable {
        final AtomicInteger calls, closed;

        Probe(AtomicInteger calls, AtomicInteger closed) {
            this.calls = calls;
            this.closed = closed;
        }

        public AiResult complete(AiRequest request) {
            calls.incrementAndGet();
            return null;
        }

        public <T> AiStructuredResult<T> complete(AiRequest request, Class<T> type, AiOutputValidator<T> validator) {
            calls.incrementAndGet();
            return null;
        }

        public AiConversationResult converse(AiConversationRequest request) {
            calls.incrementAndGet();
            return null;
        }

        public AiConversationResult converseStreaming(AiConversationRequest request, AiStreamListener listener) {
            calls.incrementAndGet();
            return null;
        }

        public void close() {
            closed.incrementAndGet();
        }
    }
}
