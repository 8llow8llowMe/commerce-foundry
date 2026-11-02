package com.commercefoundry.ai.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class AiCallContextTest {
    @Test void legacyConstructorsSelectAnthropic() {
        assertThat(new AiCallContext("id", "feature", null, null, "same-model", 100, 10, 0, false).provider()).isEqualTo("anthropic");
        assertThat(new AiCallContext("id", "feature", null, null, "same-model", 100, 10, 0, false, 1, 0, false).provider()).isEqualTo("anthropic");
    }
    @Test void explicitProviderIsRequiredAndErrorsDoNotExposeValues() {
        assertThat(new AiCallContext("id", "feature", null, null, "same-model", 100, 10, 0, false, 1, 0, false, "openai").provider()).isEqualTo("openai");
        for (String provider : new String[]{null, "private-provider-probe", ""}) {
            assertThatThrownBy(() -> new AiCallContext("id", "feature", null, null, "same-model", 100, 10, 0, false, 1, 0, false, provider))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-provider-probe");
        }
    }
}
