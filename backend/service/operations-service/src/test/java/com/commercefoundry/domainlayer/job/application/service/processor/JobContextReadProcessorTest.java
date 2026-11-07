package com.commercefoundry.domainlayer.job.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobContextReadProcessorTest {
    @Mock
    private JobRepositoryPort repository;
    @InjectMocks
    private JobContextReadProcessor processor;

    @Test
    void returnsJobDomainWithoutChangingIt() {
        var job = Job.register(20, "SIZE_CHART_GENERATION", "{}", null, 30L, 0, Instant.EPOCH, 3);
        given(repository.findById(20)).willReturn(Optional.of(job));
        assertThat(processor.findById(20).orElseThrow()).isSameAs(job);
        verify(repository).findById(20);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void missingJobRemainsEmptyForCallingContextToInterpret() {
        given(repository.findById(20)).willReturn(Optional.empty());
        assertThat(processor.findById(20)).isEmpty();
        verify(repository).findById(20);
        verifyNoMoreInteractions(repository);
    }
}
