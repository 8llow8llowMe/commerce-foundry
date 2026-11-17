package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.client.AiStructuredResult;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.domainlayer.job.domain.model.JobCancelledException;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.ai.SizeChartGenerationAiAdapter;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationInputPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationImageQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult.Image;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartImageGenerationCandidate;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartImageGenerationCandidate.Cell;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartImageGenerationCandidate.Row;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

/** IMAGE 입력 경로. 실제 Claude API 는 부르지 않는다 — AiClient 는 목이다. */
@ExtendWith(OutputCaptureExtension.class)
class SizeChartGenerationImageJobProcessorTest {
    private static final String HASH = "a".repeat(64);
    private static final String TEMPLATE = "{\"unit\":\"CM\",\"columns\":[{\"id\":11,\"name\":\"Chest\"},{\"id\":12,\"name\":\"Length\"}]}";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9, 8, 7};

    private final SizeChartGenerationInputPort input = mock(SizeChartGenerationInputPort.class);
    private final AiClient ai = mock(AiClient.class);
    private final JobExecution execution = mock(JobExecution.class);
    private final SizeChartGenerationValidationProcessor validation = new SizeChartGenerationValidationProcessor(JsonMapper.builder().build());
    private SizeChartGenerationJobProcessor handler;
    private List<byte[]> served;

    @BeforeEach
    void setUp() {
        AiProperties properties = mock(AiProperties.class);
        when(properties.maxInputChars()).thenReturn(50000);
        when(properties.features()).thenReturn(Map.of(
            "size-chart", new AiProperties.Feature(null, 8000, null, 1),
            "size-chart-image", new AiProperties.Feature(null, 8000, null, 1)));
        handler = new SizeChartGenerationJobProcessor(input, new SizeChartGenerationAiAdapter(ai, new PromptTemplates(getClass().getClassLoader(), properties)),
            validation);
        when(execution.payloadJson()).thenReturn("{\"draftId\":7,\"inputHash\":\"" + HASH + "\"}");
        when(execution.jobId()).thenReturn(9L);
        when(execution.attempt()).thenReturn(1);
        when(input.getGenerationInput(7)).thenReturn(snapshot(List.of(PNG, JPEG), List.of("image/png", "image/jpeg")));
        served = List.of(PNG, JPEG);
        serveImages();
        output(new SizeChartImageGenerationCandidate("CM", List.of(
            new Row("S", List.of(new Cell(11, new BigDecimal("40"), false), new Cell(12, new BigDecimal("60"), true))),
            new Row("M", List.of(new Cell(11, new BigDecimal("44"), true), new Cell(12, null, true))))));
    }

    @Test
    void approvedImagesReachAiWithHashCheckedBytesAndUncertainCellsBecomeWarnings() {
        String json = handler.execute(execution).resultJson();

        assertThat(json).contains("{\"code\":\"UNCERTAIN_CELL\",\"rowIndex\":0,\"columnId\":12}",
            "{\"code\":\"UNCERTAIN_CELL\",\"rowIndex\":1,\"columnId\":11}", "{\"code\":\"MISSING_CELL\",\"rowIndex\":1,\"columnId\":12}");
        // 값이 없는 칸은 uncertain 이어도 MISSING_CELL 만 낸다
        assertThat(json).doesNotContain("\"UNCERTAIN_CELL\",\"rowIndex\":1,\"columnId\":12", "uncertain", HASH, "image/png", "Chest");
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(ai).complete(request.capture(), eq(SizeChartImageGenerationCandidate.class));
        assertThat(request.getValue().feature()).isEqualTo("size-chart-image");
        assertThat(request.getValue().callerRef()).isEqualTo("job:9:1");
        assertThat(request.getValue().images()).hasSize(2);
        assertThat(request.getValue().images().get(0).mediaType()).isEqualTo("image/png");
        assertThat(request.getValue().images().get(0).bytes()).containsExactly(PNG);
        assertThat(request.getValue().images().get(1).mediaType()).isEqualTo("image/jpeg");
        // 프롬프트에는 순번 · 형식만 싣고 해시는 싣지 않는다
        assertThat(request.getValue().prompt()).contains("\"index\":0", "\"index\":1").doesNotContain(sha256(PNG), sha256(JPEG));
    }

    @Test
    void checkpointsSurroundEveryImageReadAndTheAiCall() {
        List<String> steps = new ArrayList<>();
        doAnswer(call -> { steps.add("checkpoint:" + call.getArgument(0)); return null; }).when(execution).checkpoint(anyInt(), anyString());
        doAnswer(call -> {
            List<Integer> indexes = call.getArgument(1);
            Consumer<SizeChartGenerationImageQueryResult> onImage = call.getArgument(3);
            for (int index : indexes) {
                steps.add("image:" + index);
                onImage.accept(new SizeChartGenerationImageQueryResult(index, served.get(index)));
            }
            return null;
        }).when(input).readGenerationImages(eq(7L), any(), eq(SizeChartGenerationValidationProcessor.MAX_IMAGE_BYTES), any());
        when(ai.complete(any(AiRequest.class), eq(SizeChartImageGenerationCandidate.class))).thenAnswer(call -> {
            steps.add("ai");
            return new AiStructuredResult<>(new SizeChartImageGenerationCandidate("CM", List.of(new Row("S", List.of()))), "fake", 1, 1, "end_turn", "id");
        });
        handler.execute(execution);
        assertThat(steps).containsExactly("checkpoint:5", "checkpoint:25", "checkpoint:26", "image:0", "checkpoint:30", "image:1", "checkpoint:34",
            "checkpoint:35", "ai", "checkpoint:85", "checkpoint:100");
    }

    @Test
    void changedBytesStopBeforeTheNextImageAndNeverReachAi() {
        served = List.of(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9}, JPEG);
        assertFailure("SIZE_CHART_IMAGE_CHANGED", false);
        verifyNoInteractions(ai);
    }

    @Test
    void wrongMagicBytesAreAnImageChange() {
        // 해시는 맞지만 형식이 다르다(승인 메타데이터는 PNG)
        when(input.getGenerationInput(7)).thenReturn(snapshot(List.of(JPEG), List.of("image/png")));
        served = List.of(JPEG);
        assertFailure("SIZE_CHART_IMAGE_CHANGED", false);
        verifyNoInteractions(ai);
    }

    @Test
    void missingImagesAreAnImageChange() {
        doAnswer(call -> null).when(input).readGenerationImages(anyLong(), any(), anyInt(), any());
        assertFailure("SIZE_CHART_IMAGE_CHANGED", false);
        verifyNoInteractions(ai);
    }

    @Test
    void remoteImageFailuresKeepCodeAndRetryability() {
        for (Code code : List.of(Code.SIZE_CHART_IMAGE_CHANGED, Code.SIZE_CHART_INPUT_UNAVAILABLE)) {
            boolean retryable = code == Code.SIZE_CHART_INPUT_UNAVAILABLE;
            doThrow(new SizeChartGenerationRemoteFailure(code, retryable)).when(input).readGenerationImages(anyLong(), any(), anyInt(), any());
            assertFailure(code.name(), retryable);
        }
        verifyNoInteractions(ai);
        assertThat(handler.failureNotice("SIZE_CHART_IMAGE_CHANGED")).contains("Approve the image again");
    }

    @Test
    void metadataThatDisagreesWithTheSourceNeverReadsBytes() {
        String hash = sha256(PNG);
        String source = "{\"mode\":\"IMAGE\",\"images\":[{\"sha256\":\"" + hash + "\",\"contentType\":\"image/png\"}]}";
        for (var snapshot : List.of(
            // 응답 메타데이터 해시가 sourceJson 과 다르다
            new SizeChartGenerationInputQueryResult(7, HASH, source, TEMPLATE, "IMAGE", List.of(new Image(0, "b".repeat(64), "image/png"))),
            // 형식이 다르다
            new SizeChartGenerationInputQueryResult(7, HASH, source, TEMPLATE, "IMAGE", List.of(new Image(0, hash, "image/jpeg"))),
            // 장수가 다르다
            new SizeChartGenerationInputQueryResult(7, HASH, source, TEMPLATE, "IMAGE", List.of()),
            // 허용하지 않는 형식
            new SizeChartGenerationInputQueryResult(7, HASH, source.replace("image/png", "image/gif"), TEMPLATE, "IMAGE",
                List.of(new Image(0, hash, "image/gif"))),
            // 스토리지 키 같은 추가 필드
            new SizeChartGenerationInputQueryResult(7, HASH, source.replace("\"contentType\"", "\"key\":\"products/x\",\"contentType\""), TEMPLATE,
                "IMAGE", List.of(new Image(0, hash, "image/png"))),
            // 응답 mode 는 IMAGE 인데 source 는 TEXT
            new SizeChartGenerationInputQueryResult(7, HASH, "{\"mode\":\"TEXT\",\"text\":\"S 40\"}", TEMPLATE, "IMAGE",
                List.of(new Image(0, hash, "image/png"))),
            // 응답 mode 는 TEXT 인데 이미지가 있다
            new SizeChartGenerationInputQueryResult(7, HASH, "{\"mode\":\"TEXT\",\"text\":\"S 40\"}", TEMPLATE, "TEXT",
                List.of(new Image(0, hash, "image/png"))),
            new SizeChartGenerationInputQueryResult(7, HASH, source, TEMPLATE, "VIDEO", List.of(new Image(0, hash, "image/png"))))) {
            when(input.getGenerationInput(7)).thenReturn(snapshot);
            assertFailure("SIZE_CHART_INVALID_DATA", false);
        }
        verify(input, org.mockito.Mockito.never()).readGenerationImages(anyLong(), any(), anyInt(), any());
        verifyNoInteractions(ai);
    }

    @Test
    void moreThanFourImagesNeverReachProductOrAi() {
        List<byte[]> five = List.of(PNG, JPEG, PNG.clone(), JPEG.clone(), PNG.clone());
        five.get(2)[11] = 5;
        five.get(3)[5] = 5;
        five.get(4)[11] = 6;
        when(input.getGenerationInput(7)).thenReturn(snapshot(five, List.of("image/png", "image/jpeg", "image/png", "image/jpeg", "image/png")));
        assertFailure("SIZE_CHART_INVALID_DATA", false);
        verify(input, org.mockito.Mockito.never()).readGenerationImages(anyLong(), any(), anyInt(), any());
    }

    @Test
    void invalidAiOutputIsRejectedWithoutLeakingIt(CapturedOutput logs) {
        output(new SizeChartImageGenerationCandidate("CM", List.of(new Row("person@example.invalid", List.of()))));
        assertFailure("SIZE_CHART_INVALID_DATA", false);
        output(new SizeChartImageGenerationCandidate("CM", List.of(new Row("S", List.of(new Cell(99, BigDecimal.ONE, true))))));
        assertFailure("SIZE_CHART_INVALID_DATA", false);
        output(new SizeChartImageGenerationCandidate("CM", null));
        assertFailure("SIZE_CHART_INVALID_DATA", false);
        assertThat(logs.getAll()).doesNotContain("person@example.invalid");
    }

    @Test
    void cancellationWhileReadingImagesStopsWithoutWrapping() {
        var cancelled = new JobCancelledException(JobCancelledException.Reason.LEASE_LOST);
        doThrow(cancelled).when(execution).checkpoint(eq(30), anyString());
        assertThatThrownBy(() -> handler.execute(execution)).isSameAs(cancelled);
        verifyNoInteractions(ai);
    }

    @Test
    void textModeStillUsesTheTextFeature() {
        when(input.getGenerationInput(7)).thenReturn(new SizeChartGenerationInputQueryResult(7, HASH, "{\"mode\":\"TEXT\",\"text\":\"S 40\"}", TEMPLATE));
        when(ai.complete(any(AiRequest.class), eq(SizeChartGenerationCandidate.class))).thenReturn(new AiStructuredResult<>(
            new SizeChartGenerationCandidate("CM", List.of(new SizeChartGenerationCandidate.Row("S", List.of()))), "fake", 1, 1, "end_turn", "id"));
        assertThat(handler.execute(execution).resultJson()).doesNotContain("UNCERTAIN_CELL");
        verify(input, org.mockito.Mockito.never()).readGenerationImages(anyLong(), any(), anyInt(), any());
    }

    private void serveImages() {
        doAnswer(call -> {
            List<Integer> indexes = call.getArgument(1);
            Consumer<SizeChartGenerationImageQueryResult> onImage = call.getArgument(3);
            for (int index : indexes) {
                onImage.accept(new SizeChartGenerationImageQueryResult(index, served.get(index)));
            }
            return null;
        }).when(input).readGenerationImages(eq(7L), any(), eq(SizeChartGenerationValidationProcessor.MAX_IMAGE_BYTES), any());
    }

    private SizeChartGenerationInputQueryResult snapshot(List<byte[]> approved, List<String> types) {
        StringBuilder source = new StringBuilder("{\"mode\":\"IMAGE\",\"images\":[");
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < approved.size(); i++) {
            String hash = sha256(approved.get(i));
            source.append(i == 0 ? "" : ",").append("{\"sha256\":\"").append(hash).append("\",\"contentType\":\"").append(types.get(i)).append("\"}");
            images.add(new Image(i, hash, types.get(i)));
        }
        return new SizeChartGenerationInputQueryResult(7, HASH, source.append("]}").toString(), TEMPLATE, "IMAGE", images);
    }

    private void output(SizeChartImageGenerationCandidate candidate) {
        when(ai.complete(any(AiRequest.class), eq(SizeChartImageGenerationCandidate.class)))
            .thenReturn(new AiStructuredResult<>(candidate, "fake", 1, 1, "end_turn", "provider-secret"));
    }

    private void assertFailure(String code, boolean retryable) {
        assertThatThrownBy(() -> handler.execute(execution)).isInstanceOfSatisfying(JobExecutionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(code);
            assertThat(failure.isRetryable()).isEqualTo(retryable);
            assertThat(failure.getCause()).isNull();
        });
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
