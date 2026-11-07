package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.NOW;
import static com.commercefoundry.domainlayer.sizechart.SizeChartTransportFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartDraftRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SizeChartDraftInputProcessorTest {
    private final SizeChartDraftRepositoryPort repository = mock(SizeChartDraftRepositoryPort.class);
    private final SizeChartDraftInputProcessor processor = new SizeChartDraftInputProcessor(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void returnsStoredSnapshotsEvenBeforeRegistrationResponseBindsJob() {
        when(repository.findById(7)).thenReturn(Optional.of(draft()));
        var info = processor.generationInput(7);
        assertThat(info.draftId()).isEqualTo(7);
        assertThat(info.inputHash()).isEqualTo(draft().input().inputHash());
        assertThat(info.sourceJson()).isEqualTo(draft().input().sourceJson());
        assertThat(info.templateJson()).isEqualTo(draft().input().templateJson());
        assertThat(info.toString()).doesNotContain("M 40", "columns");
    }

    @Test
    void absentIsNotFoundAndExactExpiryIsRejected() {
        when(repository.findById(7)).thenReturn(Optional.empty());
        failure(SizeChartDraftTransportErrorCode.NOT_FOUND);
        var d = draft();
        when(repository.findById(7)).thenReturn(Optional.of(new SizeChartDraft(d.id(), d.input(), null, null, d.status(), 0, NOW, null)));
        failure(SizeChartDraftTransportErrorCode.EXPIRED);
    }

    @Test
    void onlyRequestedDraftsCanSupplyInput() {
        var bound = draft().bindJob(9, NOW);
        var ready = bound.ready(SizeChartDraft.Candidate.of("{}"), NOW);
        var applied = ready.apply(SizeChartDraft.Candidate.of("{}"), new SizeChartDraft.ApplyReceipt("a".repeat(64), "{}", 100, NOW), NOW);
        for (var d : new SizeChartDraft[] {bound.fail(NOW), bound.cancel(NOW), ready, applied}) {
            when(repository.findById(7)).thenReturn(Optional.of(d));
            failure(SizeChartDraftTransportErrorCode.INVALID_STATUS);
        }
        when(repository.findById(7)).thenReturn(Optional.of(bound));
        assertThat(processor.generationInput(7).draftId()).isEqualTo(7);
    }

    private void failure(SizeChartDraftTransportErrorCode code) {
        assertThatThrownBy(() -> processor.generationInput(7)).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
