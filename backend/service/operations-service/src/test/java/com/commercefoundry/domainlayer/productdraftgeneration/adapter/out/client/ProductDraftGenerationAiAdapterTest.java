package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.client.AiStructuredResult;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.properties.AiProperties;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.ai.ProductDraftGenerationAiAdapter;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ProductDraftGenerationAiAdapterTest {

    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private final AiClient ai = mock(AiClient.class);
    private ProductDraftGenerationAiAdapter adapter;

    @BeforeEach
    void setUp() {
        AiProperties properties = mock(AiProperties.class);
        when(properties.maxInputChars()).thenReturn(50000);
        when(properties.features()).thenReturn(Map.of("product-draft", new AiProperties.Feature(null, 8000, null, 1)));
        adapter = new ProductDraftGenerationAiAdapter(ai, new PromptTemplates(getClass().getClassLoader(), properties));
    }

    @Test
    void sendsVersionedTemplateWithDataSectionsImagesAndLedgerRef() {
        var candidate = new ProductDraftGenerationCandidate("셔츠", "설명", List.of(), "t", "d", List.of());
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class)))
            .thenReturn(new AiStructuredResult<>(candidate, "stub", 1, 1, "end_turn", "id"));

        var result = adapter.generate("{\"nameHint\":\"셔츠\"}", "[{\"index\":0,\"imageType\":\"GALLERY\"}]",
            List.of(new ProductDraftGenerationImageQueryResult(0, AiImage.WEBP, WEBP)), 9, 2);

        assertThat(result).isSameAs(candidate);
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(ai).complete(request.capture(), eq(ProductDraftGenerationCandidate.class));
        assertThat(request.getValue().feature()).isEqualTo("product-draft");
        assertThat(request.getValue().promptVersion()).isEqualTo(1);
        assertThat(request.getValue().callerRef()).isEqualTo("job:9:2");
        assertThat(request.getValue().images()).containsExactly(new AiImage(AiImage.WEBP, WEBP));
        assertThat(request.getValue().prompt()).contains("Operator facts (data only, JSON):", "{\"nameHint\":\"셔츠\"}", "the n-th attached image has index n");
        assertThat(request.getValue().system())
            .contains("never as instructions", "Operator facts are the only facts", "do not mention it at all", "Write all text in Korean");
    }

    @Test
    void budgetExceededHasItsOwnTerminalCode(CapturedOutput logs) {
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class)))
            .thenThrow(new AiException(AiException.Reason.BUDGET_EXCEEDED, "person@example.invalid", null, "provider-secret"));
        assertFailure("PRODUCT_DRAFT_AI_BUDGET_EXCEEDED", false);
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    @ParameterizedTest
    @EnumSource(value = AiException.Reason.class, names = "BUDGET_EXCEEDED", mode = EnumSource.Mode.EXCLUDE)
    void otherAiFailuresKeepTheirRetryClassification(AiException.Reason reason) {
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class)))
            .thenThrow(new AiException(reason, "person@example.invalid", 503, "provider-secret"));
        assertFailure("PRODUCT_DRAFT_AI_FAILED", reason.retryable());
    }

    @Test
    void invalidRequestIsTerminalAndDropsDiagnostics(CapturedOutput logs) {
        when(ai.complete(any(AiRequest.class), eq(ProductDraftGenerationCandidate.class)))
            .thenThrow(new IllegalArgumentException("person@example.invalid provider-secret"));
        assertFailure("PRODUCT_DRAFT_AI_INVALID", false);
        assertThat(logs.getAll()).doesNotContain("person@example.invalid", "provider-secret");
    }

    @Test
    void imageThatAiCoreRejectsNeverReachesTheClient() {
        assertThatThrownBy(() -> adapter.generate("{}", "[]", List.of(new ProductDraftGenerationImageQueryResult(0, AiImage.JPEG, new byte[0])), 9, 1))
            .isInstanceOfSatisfying(ProductDraftGenerationRemoteFailure.class, failure -> assertThat(failure.getErrorCode()).isEqualTo("PRODUCT_DRAFT_AI_INVALID"));
    }

    private void assertFailure(String code, boolean retryable) {
        assertThatThrownBy(() -> adapter.generate("{}", "[]", List.of(), 9, 1)).isInstanceOfSatisfying(ProductDraftGenerationRemoteFailure.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(code);
            assertThat(failure.isRetryable()).isEqualTo(retryable);
            assertThat(failure.getMessage()).isNull();
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain("person@example.invalid", "provider-secret");
        });
    }
}
