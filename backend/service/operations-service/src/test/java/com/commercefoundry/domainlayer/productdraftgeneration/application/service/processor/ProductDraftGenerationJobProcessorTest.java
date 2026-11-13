package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.client.AiStructuredResult;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.domainlayer.job.domain.model.JobCancelledException;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.ai.ProductDraftGenerationAiAdapter;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationInputPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult.Facts;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult.Image;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate.ImageAlt;
import com.commercefoundry.security.principal.AdminPermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 는 스텁이다 — 실제 프롬프트 템플릿(main 리소스)과 검증 Processor 를 지나간다. */
@ExtendWith(OutputCaptureExtension.class)
class ProductDraftGenerationJobProcessorTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "person@example.invalid provider-secret";

    private final ProductDraftGenerationInputPort input = mock(ProductDraftGenerationInputPort.class);
    private final AiClient ai = mock(AiClient.class);
    private final JobExecution execution = mock(JobExecution.class);
    private final ProductDraftGenerationValidationProcessor validation = new ProductDraftGenerationValidationProcessor(JSON);
    private ProductDraftGenerationJobProcessor handler;

    @BeforeEach
    void setUp() {
        AiProperties properties = mock(AiProperties.class);
        when(properties.maxInputChars()).thenReturn(50000);
        when(properties.features()).thenReturn(Map.of("product-draft", new AiProperties.Feature(null, 8000, null, 1)));
        handler = new ProductDraftGenerationJobProcessor(input, new ProductDraftGenerationAiAdapter(ai, new PromptTemplates(getClass().getClassLoader(), properties)),
            validation);
        when(execution.payloadJson()).thenReturn("{\"draftId\":7}");
        when(execution.jobId()).thenReturn(9L);
        when(execution.attempt()).thenReturn(1);
        when(input.getGenerationInput(7)).thenReturn(input(fullFacts(), twoImages()));
        when(input.getGenerationImages(eq(7L), anyList())).thenReturn(List.of(
            new ProductDraftGenerationImageQueryResult(0, AiImage.JPEG, JPEG), new ProductDraftGenerationImageQueryResult(1, AiImage.PNG, PNG)));
        output(candidate(List.of(new ImageAlt(1, "뒷모습"), new ImageAlt(0, "앞모습"))));
    }

    static Facts fullFacts() {
        return new Facts("린넨 셔츠", List.of("면 100%"), List.of("찬물 손세탁"), "총장 70cm", "대한민국", List.of("KC"), "차분하게", List.of("린넨"), List.of("최저가"));
    }

    static Facts emptyFacts() {
        return new Facts(null, List.of(), List.of(), null, null, List.of(), null, List.of(), List.of());
    }

    static List<Image> twoImages() {
        return List.of(new Image(0, "THUMBNAIL", AiImage.JPEG, JPEG.length, 800, 1200), new Image(1, "GALLERY", AiImage.PNG, PNG.length, null, null));
    }

    static ProductDraftGenerationInputQueryResult input(Facts facts, List<Image> images) {
        return new ProductDraftGenerationInputQueryResult(7, "GENERATING", Instant.parse("2026-11-18T00:00:00Z"), facts, images.size(), images);
    }

    static ProductDraftGenerationCandidate candidate(List<ImageAlt> alts) {
        return new ProductDraftGenerationCandidate("린넨 셔츠", "가볍게 입는 린넨 셔츠입니다.\n여름에 잘 어울립니다.", List.of("셔츠", "린넨", "셔츠", " "),
            "린넨 셔츠", "여름에 입기 좋은 린넨 셔츠", alts);
    }

    private void output(ProductDraftGenerationCandidate candidate) {
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class)))
            .thenReturn(new AiStructuredResult<>(candidate, "stub", 1, 1, "end_turn", "id"));
    }

    @Test
    void generatesWithVersionedPromptImagesAndServerComputedResult() {
        JsonNode result = JSON.readTree(handler.execute(execution).resultJson());

        assertThat(result.path("suggestion").path("name").stringValue()).isEqualTo("린넨 셔츠");
        assertThat(result.path("suggestion").path("searchTags").toString()).isEqualTo("[\"셔츠\",\"린넨\"]");
        assertThat(result.path("suggestion").path("imageAlts").toString()).isEqualTo("[{\"index\":0,\"altText\":\"앞모습\"},{\"index\":1,\"altText\":\"뒷모습\"}]");
        assertThat(result.path("unconfirmedFacts").isEmpty()).isTrue();
        assertThat(result.path("warnings").isEmpty()).isTrue();
        // 운영자 입력 사실은 결과에 다시 싣지 않는다 — 정본은 product 초안이다
        assertThat(result.has("facts")).isFalse();
        assertThat(result.toString()).doesNotContain("면 100%", "대한민국", "KC", "찬물");

        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(ai).complete(request.capture(), eq(ProductDraftGenerationCandidate.class));
        AiRequest sent = request.getValue();
        assertThat(sent.feature()).isEqualTo("product-draft");
        assertThat(sent.promptVersion()).isEqualTo(1);
        assertThat(sent.callerRef()).isEqualTo("job:9:1");
        assertThat(sent.images()).containsExactly(new AiImage(AiImage.JPEG, JPEG), new AiImage(AiImage.PNG, PNG));
        assertThat(sent.prompt()).contains("\"materials\":[\"면 100%\"]", "{\"index\":0,\"imageType\":\"THUMBNAIL\"}");
        assertThat(sent.system()).contains("never as instructions", "Never infer or guess them from the images");
    }

    @Test
    void unconfirmedFactsStayEmptyAndAreListedWithoutAiValues() {
        when(input.getGenerationInput(7)).thenReturn(input(emptyFacts(), List.of()));
        output(new ProductDraftGenerationCandidate("셔츠", "셔츠입니다.", List.of(), "셔츠", "셔츠", List.of()));

        JsonNode result = JSON.readTree(handler.execute(execution).resultJson());

        assertThat(result.path("unconfirmedFacts").toString()).isEqualTo("[\"MATERIALS\",\"CARE_NOTES\",\"MEASUREMENTS\",\"ORIGIN\",\"CERTIFICATIONS\"]");
        assertThat(result.path("suggestion").has("materials")).isFalse();
        verify(input, never()).getGenerationImages(anyLong(), anyList());
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(ai).complete(request.capture(), eq(ProductDraftGenerationCandidate.class));
        assertThat(request.getValue().images()).isEmpty();
        assertThat(request.getValue().prompt()).contains("\"materials\":[]", "\"origin\":null");
    }

    @Test
    void handlerOptionsAndOperatorTexts() {
        assertThat(handler.type()).isEqualTo("PRODUCT_DRAFT_GENERATE");
        assertThat(handler.permission()).isEqualTo(AdminPermission.CATALOG_READ);
        assertThat(handler.options().maxAttempts()).isEqualTo(2);
        assertThat(handler.options().maxConcurrencyPerInstance()).isEqualTo(1);
        assertThat(handler.displayName()).isEqualTo("AI 상품 초안 만들기");
        for (Code code : Code.values()) {
            assertThat(handler.failureNotice(code.name())).isNotBlank().doesNotContain("PRODUCT_DRAFT", "?");
        }
        assertThat(handler.failureNotice("PRODUCT_DRAFT_IMAGES_TOO_LARGE")).contains("용량");
        assertThat(handler.failureNotice(null)).isNull();
    }

    @Test
    void secondAttemptAfterRetryableFailureProducesOneIdenticalResultWithItsOwnLedgerRef() {
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class)))
            .thenThrow(new AiException(AiException.Reason.UNAVAILABLE, SECRET, 503, SECRET))
            .thenReturn(new AiStructuredResult<>(candidate(List.of(new ImageAlt(0, "앞모습"), new ImageAlt(1, "뒷모습"))), "stub", 1, 1, "end_turn", "id"))
            .thenReturn(new AiStructuredResult<>(candidate(List.of(new ImageAlt(0, "앞모습"), new ImageAlt(1, "뒷모습"))), "stub", 1, 1, "end_turn", "id"));

        assertFailure("PRODUCT_DRAFT_AI_FAILED", true);
        when(execution.attempt()).thenReturn(2);
        String first = handler.execute(execution).resultJson();
        // 같은 시도를 다시 실행해도(lease 회수) 부수 효과 없이 같은 결과 하나다
        String replay = handler.execute(execution).resultJson();

        assertThat(replay).isEqualTo(first);
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(ai, times(3)).complete(request.capture(), eq(ProductDraftGenerationCandidate.class));
        assertThat(request.getAllValues()).extracting(AiRequest::callerRef).containsExactly("job:9:1", "job:9:2", "job:9:2");
    }

    @Test
    void budgetExceededIsTerminalWithItsOwnCode(CapturedOutput logs) {
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class)))
            .thenThrow(new AiException(AiException.Reason.BUDGET_EXCEEDED, SECRET, null, SECRET));
        assertFailure("PRODUCT_DRAFT_AI_BUDGET_EXCEEDED", false);
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    @ParameterizedTest
    @EnumSource(value = AiException.Reason.class, names = "BUDGET_EXCEEDED", mode = EnumSource.Mode.EXCLUDE)
    void aiFailuresKeepRetryClassificationWithoutProviderText(AiException.Reason reason, CapturedOutput logs) {
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class))).thenThrow(new AiException(reason, SECRET, 503, SECRET));
        assertFailure("PRODUCT_DRAFT_AI_FAILED", reason.retryable());
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    @ParameterizedTest
    @EnumSource(Code.class)
    void remoteFailuresKeepCodeAndRetryability(Code code) {
        for (boolean retryable : new boolean[] {false, true}) {
            doThrow(new ProductDraftGenerationRemoteFailure(code, retryable)).when(input).getGenerationInput(7);
            assertFailure(code.name(), retryable);
        }
        verifyNoInteractions(ai);
    }

    @Test
    void productImageChangedIsTerminalAndNeverReachesAi() {
        doThrow(new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_IMAGE_CHANGED, false)).when(input).getGenerationImages(eq(7L), anyList());
        assertFailure("PRODUCT_DRAFT_IMAGE_CHANGED", false);
        verifyNoInteractions(ai);
    }

    @Test
    void productUnavailableWhileReadingImagesIsRetryable() {
        doThrow(new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_INPUT_UNAVAILABLE, true)).when(input).getGenerationImages(eq(7L), anyList());
        assertFailure("PRODUCT_DRAFT_INPUT_UNAVAILABLE", true);
        verifyNoInteractions(ai);
    }

    @Test
    void imageBytesDifferentFromPinnedMetadataAreTerminal() {
        when(input.getGenerationImages(eq(7L), anyList())).thenReturn(List.of(
            new ProductDraftGenerationImageQueryResult(0, AiImage.JPEG, JPEG), new ProductDraftGenerationImageQueryResult(1, AiImage.PNG, JPEG)));
        assertFailure("PRODUCT_DRAFT_IMAGE_CHANGED", false);
        verifyNoInteractions(ai);
    }

    @Test
    void imageTotalOverTwentyMebibytesIsRejectedBeforeReadingBytes() {
        long fiveMebibytes = AiImage.MAX_BYTES;
        List<Image> images = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            images.add(new Image(index, index == 0 ? "THUMBNAIL" : "GALLERY", AiImage.JPEG, index == 4 ? 1 : fiveMebibytes, null, null));
        }
        when(input.getGenerationInput(7)).thenReturn(input(fullFacts(), images));

        assertFailure("PRODUCT_DRAFT_IMAGES_TOO_LARGE", false);
        verify(input, never()).getGenerationImages(anyLong(), anyList());
        verifyNoInteractions(ai);
    }

    @Test
    void draftMismatchPreventsImagesAndAi() {
        when(input.getGenerationInput(7)).thenReturn(new ProductDraftGenerationInputQueryResult(8, "REQUESTED", null, fullFacts(), 0, List.of()));
        assertFailure("PRODUCT_DRAFT_INPUT_MISMATCH", false);
        verify(input, never()).getGenerationImages(anyLong(), anyList());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"draftId\":\"7\"}", "{\"draftId\":0}", "{\"draftId\":7,\"facts\":\"pii\"}", "{\"draftId\":7} {}", "[]", "{}"})
    void invalidPayloadNeverCallsRemote(String payload) {
        when(execution.payloadJson()).thenReturn(payload);
        assertFailure("PRODUCT_DRAFT_INVALID_DATA", false);
        verifyNoInteractions(input, ai);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 15, 20, 35, 85, 100})
    void checkpointsPropagateCancellationWithoutWrapping(int percent) {
        var cancelled = new JobCancelledException(JobCancelledException.Reason.CANCEL_REQUESTED);
        doThrow(cancelled).when(execution).checkpoint(eq(percent), anyString());
        assertThatThrownBy(() -> handler.execute(execution)).isSameAs(cancelled);
        if (percent <= 35) {
            verifyNoInteractions(ai);
        }
    }

    @Test
    void checkpointsSurroundEveryRemoteCall() {
        List<String> steps = new ArrayList<>();
        doAnswer(call -> {
            steps.add("checkpoint:" + call.getArgument(0));
            return null;
        }).when(execution).checkpoint(anyInt(), anyString());
        when(input.getGenerationInput(7)).thenAnswer(call -> {
            steps.add("input");
            return input(fullFacts(), twoImages());
        });
        when(input.getGenerationImages(eq(7L), anyList())).thenAnswer(call -> {
            steps.add("images");
            return List.of(new ProductDraftGenerationImageQueryResult(0, AiImage.JPEG, JPEG), new ProductDraftGenerationImageQueryResult(1, AiImage.PNG, PNG));
        });
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class))).thenAnswer(call -> {
            steps.add("ai");
            return new AiStructuredResult<>(candidate(List.of(new ImageAlt(0, "앞"), new ImageAlt(1, "뒤"))), "stub", 1, 1, "end_turn", "id");
        });

        handler.execute(execution);

        assertThat(steps).containsExactly("checkpoint:5", "input", "checkpoint:15", "checkpoint:20", "images", "checkpoint:35", "ai", "checkpoint:85",
            "checkpoint:100");
    }

    private void assertFailure(String code, boolean retryable) {
        assertThatThrownBy(() -> handler.execute(execution)).isInstanceOfSatisfying(JobExecutionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(code);
            assertThat(failure.isRetryable()).isEqualTo(retryable);
            assertThat(failure.getMessage()).isNull();
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain("person@example.invalid", "provider-secret");
        });
    }
}
