package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.sizechartgeneration.application.command.SizeChartGenerationRegisterCommand;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationErrorCode;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationReceiptPort;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SizeChartGenerationReceiptProcessorTest {
    private static final String HASH = "a".repeat(64);
    private static final Instant EXPIRY = Instant.parse("2026-11-07T00:01:00.123456789Z");
    @Mock
    private SizeChartGenerationReceiptPort receipts;
    private SizeChartGenerationReceiptProcessor processor;

    @BeforeEach
    void setUp() {
        var results = new SizeChartGenerationResultProcessor(receipts, JsonMapper.builder().build());
        processor = new SizeChartGenerationReceiptProcessor(receipts, results, Clock.fixed(EXPIRY.minusSeconds(60), ZoneOffset.UTC));
    }

    @Test
    void replayDefensivelyNormalizesAnInMemoryReceiptAsWellAsTheCommand() {
        given(receipts.findByDraftId(10)).willReturn(Optional.of(new SizeChartGenerationReceipt(10, 20, HASH, 30, EXPIRY)));
        var original = new SizeChartGenerationRegisterCommand(10, HASH, 30, EXPIRY);
        var reloaded = new SizeChartGenerationRegisterCommand(10, HASH, 30, EXPIRY.truncatedTo(ChronoUnit.MICROS));
        assertThat(processor.replay(original)).isEqualTo(processor.replay(reloaded));
        assertThat(processor.replay(original)).hasValueSatisfying(info -> assertThat(info.jobId()).isEqualTo(20));
    }

    @Test
    void recordWritesMicrosecondsBeforePersistenceCanRoundNanosUp() {
        var command = new SizeChartGenerationRegisterCommand(10, HASH, 30, EXPIRY);
        var request = processor.request(command);
        var job = Job.register(20, request.type(), request.payloadJson(), request.dedupKey(), 30L, 0, EXPIRY.minusSeconds(60), 3);
        processor.record(command, job);
        var captor = ArgumentCaptor.forClass(SizeChartGenerationReceipt.class);
        verify(receipts).insert(captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(Instant.parse("2026-11-07T00:01:00.123456Z"));
    }

    @Test
    void nullExpiryStillUsesTheDomainValidationError() {
        var command = new SizeChartGenerationRegisterCommand(10, HASH, 30, null);
        assertThatThrownBy(() -> processor.request(command)).isInstanceOfSatisfying(SizeChartGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SizeChartGenerationErrorCode.INVALID_REQUEST));
    }
}
