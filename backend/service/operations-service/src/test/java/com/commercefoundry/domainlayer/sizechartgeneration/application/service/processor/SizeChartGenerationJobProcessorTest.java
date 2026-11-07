package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.client.AiStructuredResult;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.domainlayer.job.domain.model.JobCancelledException;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.ai.SizeChartGenerationAiAdapter;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationAiPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationInputPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate.Cell;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate.Row;
import com.commercefoundry.security.principal.AdminPermission;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class SizeChartGenerationJobProcessorTest {
    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "{\"mode\":\"TEXT\",\"text\":\"Chest S 40 M 44\"}";
    private static final String TEMPLATE = "{\"unit\":\"CM\",\"columns\":[{\"id\":11,\"name\":\"Chest\"},{\"id\":12,\"name\":\"Length\"}]}";
    private final SizeChartGenerationInputPort input = mock(SizeChartGenerationInputPort.class);
    private final AiClient ai = mock(AiClient.class);
    private final JobExecution execution = mock(JobExecution.class);
    private final SizeChartGenerationValidationProcessor validation = new SizeChartGenerationValidationProcessor(JsonMapper.builder().build());
    private SizeChartGenerationJobProcessor handler;

    @BeforeEach
    void setUp() {
        AiProperties properties = mock(AiProperties.class);
        when(properties.maxInputChars()).thenReturn(50000);
        when(properties.features()).thenReturn(Map.of("size-chart", new AiProperties.Feature(null, 8000, null, 1)));
        var templates = new PromptTemplates(getClass().getClassLoader(), properties);
        handler = new SizeChartGenerationJobProcessor(input, new SizeChartGenerationAiAdapter(ai, templates), validation);
        when(execution.payloadJson()).thenReturn("{\"draftId\":7,\"inputHash\":\"" + HASH + "\"}");
        when(execution.jobId()).thenReturn(9L);
        when(execution.attempt()).thenReturn(1);
        when(input.getGenerationInput(7)).thenReturn(snapshot(SOURCE, TEMPLATE));
        output(new SizeChartGenerationCandidate("CM", List.of(
            new Row("M", List.of(new Cell(11, new BigDecimal("40")))),
            new Row("S", List.of(new Cell(11, new BigDecimal("10")), new Cell(12, null))))));
    }

    @Test
    void validTextUsesVersionedTypedAiAndServerWarnings() {
        String json = handler.execute(execution).resultJson();
        assertThat(json).contains("MISSING_CELL", "SIZE_ORDER", "MEASUREMENT_OUTLIER", "MEASUREMENT_ORDER", "\"value\":null");
        assertThat(json).doesNotContain("sourceJson", "templateJson", "Chest", "provider-secret");
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(32768);
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(ai).complete(request.capture(), eq(SizeChartGenerationCandidate.class));
        assertThat(request.getValue().feature()).isEqualTo("size-chart");
        assertThat(request.getValue().promptVersion()).isEqualTo(1);
        assertThat(request.getValue().callerRef()).isEqualTo("job:9:1");
        assertThat(request.getValue().images()).isEmpty();
        assertThat(handler.type()).isEqualTo("SIZE_CHART_GENERATION");
        assertThat(handler.permission()).isEqualTo(AdminPermission.CATALOG_READ);
        assertThat(handler.options().maxAttempts()).isEqualTo(2);
        assertThat(handler.options().maxConcurrencyPerInstance()).isEqualTo(1);
        assertThat(handler.displayName()).doesNotContain("?");
        assertThat(handler.failureNotice("UNKNOWN")).doesNotContain("?");
    }

    @Test
    void hashMismatchPreventsAi() {
        when(input.getGenerationInput(7)).thenReturn(new SizeChartGenerationInputQueryResult(7, "b".repeat(64), SOURCE, TEMPLATE));
        assertSafeFailure("SIZE_CHART_INPUT_MISMATCH");
        verifyNoInteractions(ai);
    }

    @Test
    void draftMismatchPreventsAi() {
        when(input.getGenerationInput(7)).thenReturn(new SizeChartGenerationInputQueryResult(8, HASH, SOURCE, TEMPLATE));
        assertSafeFailure("SIZE_CHART_INPUT_MISMATCH");
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"mode\":\"IMAGE\",\"imageId\":1}",
        "{\"mode\":\"TEXT\",\"text\":\"person@example.invalid\"}",
        "{\"mode\":\"TEXT\",\"text\":\"Alice Smith S 40\"}",
        "{\"mode\":\"TEXT\",\"text\":\"https://example.invalid\"}",
        "{\"mode\":\"TEXT\",\"text\":\"<script>fetch()</script>\"}"})
    void unsafeSourceAndImageModeNeverReachAi(String source) {
        when(input.getGenerationInput(7)).thenReturn(snapshot(source, TEMPLATE));
        assertSafeFailure("SIZE_CHART_INVALID_DATA");
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"unit\":\"MM\",\"columns\":[{\"id\":11,\"name\":\"Chest\"}]}",
        "{\"unit\":\"CM\",\"columns\":[{\"id\":11,\"name\":\"Chest\"},{\"id\":11,\"name\":\"Length\"}]}",
        "{\"unit\":\"CM\",\"columns\":[]}"})
    void invalidTemplatePreventsAi(String template) {
        when(input.getGenerationInput(7)).thenReturn(snapshot(SOURCE, template));
        assertSafeFailure("SIZE_CHART_INVALID_DATA");
        verifyNoInteractions(ai);
    }

    @Test
    void oversizedSourcePreventsAi() {
        when(input.getGenerationInput(7)).thenReturn(snapshot(" ".repeat(32769), TEMPLATE));
        assertSafeFailure("SIZE_CHART_INVALID_DATA");
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"draftId\":\"7\",\"inputHash\":\"a\"}", "{\"draftId\":7,\"inputHash\":\"a\",\"source\":\"pii\"}",
        "{\"draftId\":7,\"inputHash\":\"a\"} {}"})
    void invalidPayloadNeverCallsRemote(String payload) {
        when(execution.payloadJson()).thenReturn(payload);
        assertSafeFailure("SIZE_CHART_INVALID_DATA");
        verifyNoInteractions(input, ai);
    }

    @Test
    void rejectsUnknownColumnsUnsafeLabelsAndOutOfRangeNumbers() {
        for (var candidate : List.of(
            new SizeChartGenerationCandidate("CM", List.of(new Row("S", List.of(new Cell(99, BigDecimal.ONE))))),
            new SizeChartGenerationCandidate("CM", List.of(new Row("person@example.invalid", List.of()))),
            new SizeChartGenerationCandidate("CM", List.of(new Row("S", List.of(new Cell(11, new BigDecimal("10000")))))),
            new SizeChartGenerationCandidate("CM", List.of(new Row("S", List.of(new Cell(11, new BigDecimal("-1")))))),
            new SizeChartGenerationCandidate("CM", List.of(new Row("S", List.of(new Cell(11, new BigDecimal("1.001")))))),
            new SizeChartGenerationCandidate("INCH", List.of(new Row("S", List.of()))))) {
            output(candidate);
            assertSafeFailure("SIZE_CHART_INVALID_DATA");
        }
    }

    @Test
    void rejectsOversizedOutcomeEvenWhenRowsAndCellsAreBounded() {
        List<Long> ids = new ArrayList<>();
        for (long id = Long.MAX_VALUE - 20; id < Long.MAX_VALUE; id++) {
            ids.add(id);
        }
        List<Row> rows = new ArrayList<>();
        for (int row = 1; row <= 30; row++) {
            rows.add(new Row(Integer.toString(row), List.of()));
        }
        assertThatThrownBy(() -> validation.outcome(new SizeChartGenerationCandidate("CM", rows),
            new SizeChartGenerationValidationProcessor.Template("CM", ids))).isInstanceOf(JobExecutionException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 25, 35, 85, 100})
    void checkpointsPropagateCancellationWithoutWrapping(int percent) {
        var cancelled = new JobCancelledException(JobCancelledException.Reason.LEASE_LOST);
        doThrow(cancelled).when(execution).checkpoint(eq(percent), anyString());
        assertThatThrownBy(() -> handler.execute(execution)).isSameAs(cancelled);
        if (percent <= 35) {
            verifyNoInteractions(ai);
        }
    }

    @Test
    void successfulCheckpointsSurroundBothRemoteCalls() {
        List<String> steps = new ArrayList<>();
        doAnswer(call -> { steps.add("checkpoint:" + call.getArgument(0)); return null; })
            .when(execution).checkpoint(anyInt(), anyString());
        when(input.getGenerationInput(7)).thenAnswer(call -> { steps.add("product"); return snapshot(SOURCE, TEMPLATE); });
        when(ai.complete(any(AiRequest.class), eq(SizeChartGenerationCandidate.class))).thenAnswer(call -> {
            steps.add("ai");
            return new AiStructuredResult<>(new SizeChartGenerationCandidate("CM", List.of(new Row("S", List.of()))),
                "fake", 1, 1, "end_turn", "id");
        });
        handler.execute(execution);
        assertThat(steps).containsExactly("checkpoint:5", "product", "checkpoint:25", "checkpoint:35", "ai", "checkpoint:85", "checkpoint:100");
    }

    @ParameterizedTest
    @EnumSource(AiException.Reason.class)
    void aiFailuresKeepRetryClassificationAndNeverLeakProviderText(AiException.Reason reason, CapturedOutput logs) {
        when(ai.complete(any(AiRequest.class), eq(SizeChartGenerationCandidate.class)))
            .thenThrow(new AiException(reason, "person@example.invalid", 503, "provider-secret"));
        assertThatThrownBy(() -> handler.execute(execution)).isInstanceOfSatisfying(JobExecutionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo("SIZE_CHART_AI_FAILED");
            assertThat(failure.isRetryable()).isEqualTo(reason.retryable());
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain("person@example.invalid", "provider-secret");
        });
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    @ParameterizedTest
    @EnumSource(Code.class)
    void translatesOwnedRemoteFailuresWithoutChangingCodeOrRetryability(Code code, CapturedOutput logs) {
        for (boolean retryable : new boolean[] {false, true}) {
            var failure = new SizeChartGenerationRemoteFailure(code, retryable);
            doThrow(failure).when(input).getGenerationInput(7);
            assertTranslatedFailure(handler, code, retryable);
            verifyNoInteractions(ai);

            var aiPort = mock(SizeChartGenerationAiPort.class);
            doReturn(snapshot(SOURCE, TEMPLATE)).when(input).getGenerationInput(7);
            when(aiPort.generate(SOURCE, TEMPLATE, 9, 1)).thenThrow(failure);
            assertTranslatedFailure(new SizeChartGenerationJobProcessor(input, aiPort, validation), code, retryable);
        }
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    private void assertTranslatedFailure(SizeChartGenerationJobProcessor processor, Code code, boolean retryable) {
        assertThatThrownBy(() -> processor.execute(execution)).isInstanceOfSatisfying(JobExecutionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(code.name());
            assertThat(failure.isRetryable()).isEqualTo(retryable);
            assertThat(failure.getMessage()).isNull();
            assertThat(failure.getCause()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"S chest 50", "S CHEST 50", "S cHeSt 50"})
    void selectedTemplateLabelsMatchWithoutCaseSensitivity(String sourceText) {
        when(input.getGenerationInput(7)).thenReturn(snapshot("{\"mode\":\"TEXT\",\"text\":\"" + sourceText + "\"}", TEMPLATE));
        assertThat(handler.execute(execution).resultJson()).contains("\"unit\":\"CM\"");
        verify(ai).complete(any(AiRequest.class), eq(SizeChartGenerationCandidate.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SChest 50", "ChestM 50", "chestChest 50", "Chest50", "Chest_ S 50", "Chestperson@example.invalid S 50",
        "S chest Alice Smith 50", "S bust 50", "S \u80f8Chest 50"})
    void partialLabelsAndUnapprovedProseNeverReachAi(String sourceText) {
        when(input.getGenerationInput(7)).thenReturn(snapshot("{\"mode\":\"TEXT\",\"text\":\"" + sourceText + "\"}", TEMPLATE));
        assertSafeFailure("SIZE_CHART_INVALID_DATA");
        verifyNoInteractions(ai);
    }

    private SizeChartGenerationInputQueryResult snapshot(String source, String template) {
        return new SizeChartGenerationInputQueryResult(7, HASH, source, template);
    }

    private void output(SizeChartGenerationCandidate candidate) {
        when(ai.complete(any(AiRequest.class), eq(SizeChartGenerationCandidate.class)))
            .thenReturn(new AiStructuredResult<>(candidate, "fake", 1, 1, "end_turn", "provider-secret"));
    }

    private void assertSafeFailure(String code) {
        assertThatThrownBy(() -> handler.execute(execution)).isInstanceOfSatisfying(JobExecutionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(code);
            assertThat(failure.getCause()).isNull();
        });
    }
}
