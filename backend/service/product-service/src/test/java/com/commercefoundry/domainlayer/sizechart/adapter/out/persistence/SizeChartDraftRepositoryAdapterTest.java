package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartDraftEntity;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartDraftRepository;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SizeChartDraftRepositoryAdapterTest {

    private static final String PRIVATE_MARKER = "PRIVATE_CONTENT_MARKER";
    private final SizeChartDraftRepository repository = mock(SizeChartDraftRepository.class);
    private final SizeChartDraftRepositoryAdapter adapter = new SizeChartDraftRepositoryAdapter(repository);

    @Test
    void malformedJsonDoesNotExposeContentInExceptionCauseOrLogs(CapturedOutput output) {
        assertThatThrownBy(() -> adapter.insert(draft("{\"text\":\"" + PRIVATE_MARKER, "{}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid draft JSON")
            .hasNoCause();
        assertThat(output.getAll()).doesNotContain(PRIVATE_MARKER);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "123", "\"text\"", "{} {}", "[] trailing"})
    void scalarOrTrailingContentIsRejectedBeforePersist(String json) {
        assertThatThrownBy(() -> adapter.insert(draft(json, "{}"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void malformedTemplateIsAlsoRejected() {
        assertThatThrownBy(() -> adapter.insert(draft("{}", "invalid"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void entityAndBuilderStringRepresentationsExcludeAllStoredJson() {
        SizeChartDraftEntity.SizeChartDraftEntityBuilder builder = SizeChartDraftEntity.builder()
            .sourceJson(PRIVATE_MARKER).templateJson(PRIVATE_MARKER).candidateJson(PRIVATE_MARKER).applyReceiptJson(PRIVATE_MARKER);
        assertThat(builder.toString()).doesNotContain(PRIVATE_MARKER);
        assertThat(builder.build().toString()).doesNotContain(PRIVATE_MARKER);
    }

    private static SizeChartDraft draft(String source, String template) {
        SizeChartDraft.Input input = new SizeChartDraft.Input(10L, 20L, 0L, 100L,
            UUID.fromString("11111111-1111-4111-8111-111111111111"), "a".repeat(64), "b".repeat(64), source, template, null, null);
        return SizeChartDraft.request(1L, input, Instant.parse("2026-11-08T00:00:00Z"));
    }
}
