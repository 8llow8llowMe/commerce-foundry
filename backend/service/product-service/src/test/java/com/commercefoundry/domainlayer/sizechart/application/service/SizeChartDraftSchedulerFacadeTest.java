package com.commercefoundry.domainlayer.sizechart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.sizechart.application.service.processor.SizeChartDraftCleanupProcessor;
import com.commercefoundry.global.config.SizeChartDraftProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SizeChartDraftSchedulerFacadeTest {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private final SizeChartDraftCleanupProcessor processor = mock(SizeChartDraftCleanupProcessor.class);
    private final SizeChartDraftProperties properties = new SizeChartDraftProperties();
    private final SizeChartDraftSchedulerFacade facade = new SizeChartDraftSchedulerFacade(processor, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void fullBacklogCannotExceedConfiguredBatchBudget() {
        properties.setCleanupBatchSize(2);
        properties.setCleanupMaxBatchesPerRun(3);
        when(processor.deleteExpiredBatch(NOW, 2)).thenReturn(2);
        assertThat(facade.deleteExpiredDrafts()).isEqualTo(6);
        verify(processor, times(3)).deleteExpiredBatch(NOW, 2);
    }

    @Test
    void partialBatchStopsThisRunWithSameCutoff() {
        properties.setCleanupBatchSize(2);
        when(processor.deleteExpiredBatch(NOW, 2)).thenReturn(2, 1);
        assertThat(facade.deleteExpiredDrafts()).isEqualTo(3);
        verify(processor, times(2)).deleteExpiredBatch(NOW, 2);
    }

    @Test
    void emptyOrConcurrentlyDeletedBatchStopsImmediately() {
        when(processor.deleteExpiredBatch(NOW, 100)).thenReturn(0);
        assertThat(facade.deleteExpiredDrafts()).isZero();
        verify(processor).deleteExpiredBatch(NOW, 100);
    }
}
