package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.ai.SizeChartGenerationAiAdapter;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SizeChartGenerationAiAdapterTest {
    private final AiClient ai = mock(AiClient.class);
    private SizeChartGenerationAiAdapter adapter;

    @BeforeEach
    void setUp() {
        AiProperties properties = mock(AiProperties.class);
        when(properties.maxInputChars()).thenReturn(50000);
        when(properties.features()).thenReturn(Map.of("size-chart", new AiProperties.Feature(null, 8000, null, 1)));
        adapter = new SizeChartGenerationAiAdapter(ai, new PromptTemplates(getClass().getClassLoader(), properties));
    }

    @ParameterizedTest
    @EnumSource(AiException.Reason.class)
    void reportsOwnedSafeFailureWithOriginalRetryClassification(AiException.Reason reason, CapturedOutput logs) {
        when(ai.complete(any(AiRequest.class), eq(SizeChartGenerationCandidate.class)))
            .thenThrow(new AiException(reason, "person@example.invalid", 503, "provider-secret"));
        assertFailure("SIZE_CHART_AI_FAILED", reason.retryable());
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    @Test
    void invalidRequestIsTerminalAndDropsDiagnostics(CapturedOutput logs) {
        when(ai.complete(any(AiRequest.class), eq(SizeChartGenerationCandidate.class)))
            .thenThrow(new IllegalArgumentException("person@example.invalid provider-secret"));
        assertFailure("SIZE_CHART_AI_INVALID", false);
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    private void assertFailure(String code, boolean retryable) {
        assertThatThrownBy(() -> adapter.generate("{}", "{}", 9, 1))
            .isInstanceOfSatisfying(SizeChartGenerationRemoteFailure.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo(code);
                assertThat(failure.isRetryable()).isEqualTo(retryable);
                assertThat(failure.getMessage()).isNull();
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getSuppressed()).isEmpty();
                assertThat(failure.toString()).doesNotContain("person@example.invalid", "provider-secret");
            });
    }
}
