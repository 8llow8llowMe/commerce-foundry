package com.commercefoundry.domainlayer.knowledge.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeSearchCommand;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeSearchInfo;
import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeSearchCriteria;
import com.commercefoundry.domainlayer.knowledge.application.port.in.KnowledgeSearchUseCase;
import com.commercefoundry.domainlayer.knowledge.application.port.out.KnowledgeRepositoryPort;
import com.commercefoundry.domainlayer.knowledge.application.port.out.query.KnowledgeSearchQueryResult;
import com.commercefoundry.domainlayer.knowledge.application.service.processor.KnowledgeProcessor;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeSearchFacadeTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private final KnowledgeSearchCommand command = new KnowledgeSearchCommand("PrivateQuestion", KnowledgeLocale.EN, KnowledgeCategory.SHIPPING, 5);

    @Test
    void sameServicePortReadsClockExactlyOnceAndDelegatesCutoff() {
        KnowledgeProcessor processor = mock(KnowledgeProcessor.class);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        when(processor.search(command, NOW)).thenReturn(List.of());
        KnowledgeSearchUseCase useCase = new KnowledgeSearchFacade(processor, clock);
        assertThat(useCase.search(command)).isEmpty();
        verify(clock).instant();
        verify(processor).search(command, NOW);
        assertThat(KnowledgeSearchCommand.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("question", "locale", "category", "topN");
    }

    @Test
    void processorMapsEvidenceWithoutExposingRawContentInToStringAndPropagatesFailures() {
        KnowledgeRepositoryPort port = mock(KnowledgeRepositoryPort.class);
        KnowledgeProcessor processor = new KnowledgeProcessor(port, mock(SnowflakeIdGenerator.class));
        var criteria = KnowledgeSearchCriteria.from(command);
        var row = new KnowledgeSearchQueryResult(10, 11, 2, "Private title", "Private body", KnowledgeCategory.SHIPPING,
            KnowledgeType.POLICY, KnowledgeLocale.EN, 4, NOW, null);
        when(port.search(criteria, NOW)).thenReturn(List.of(row));
        var results = processor.search(command, NOW);
        assertThat(results).containsExactly(new KnowledgeSearchInfo(10, 11, 2, "Private title", "Private body", KnowledgeCategory.SHIPPING,
            KnowledgeType.POLICY, KnowledgeLocale.EN, 4, NOW, null));
        assertThat(row.toString()).doesNotContain("Private title", "Private body");
        assertThat(results.getFirst().toString()).doesNotContain("Private title", "Private body");
        var failure = new IllegalStateException("database unavailable");
        when(port.search(criteria, NOW)).thenThrow(failure);
        assertThatThrownBy(() -> processor.search(command, NOW)).isSameAs(failure);
    }
}
