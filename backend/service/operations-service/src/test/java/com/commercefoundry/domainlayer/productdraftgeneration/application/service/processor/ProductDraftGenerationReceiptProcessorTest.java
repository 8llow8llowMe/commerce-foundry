package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobRegistration;
import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationRegistrationRaceException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationReceiptPort;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ProductDraftGenerationReceiptProcessorTest {
    private static final String HASH = "a".repeat(64);
    private static final Instant EXPIRY = Instant.parse("2026-11-07T00:01:00.123456789Z");
    @Mock
    private ProductDraftGenerationReceiptPort receipts;
    private ProductDraftGenerationReceiptProcessor processor;

    @BeforeEach
    void setUp() {
        var results = new ProductDraftGenerationResultProcessor(receipts, JsonMapper.builder().build());
        processor = new ProductDraftGenerationReceiptProcessor(receipts, results, Clock.fixed(EXPIRY.minusSeconds(60), ZoneOffset.UTC));
    }

    @Test
    void replayDefensivelyNormalizesAnInMemoryReceiptAsWellAsTheCommand() {
        given(receipts.findByDraftIdAndGenerationNo(10, 2)).willReturn(Optional.of(new ProductDraftGenerationReceipt(10, 2, 20, HASH, 30, EXPIRY)));
        var original = new ProductDraftGenerationRegisterCommand(10, 2, HASH, 30, EXPIRY);
        var reloaded = new ProductDraftGenerationRegisterCommand(10, 2, HASH, 30, EXPIRY.truncatedTo(ChronoUnit.MICROS));
        assertThat(processor.replay(original)).isEqualTo(processor.replay(reloaded));
        assertThat(processor.replay(original)).hasValueSatisfying(info -> {
            assertThat(info.jobId()).isEqualTo(20);
            assertThat(info.generationNo()).isEqualTo(2);
        });
    }

    @Test
    void replayLooksUpTheRequestedGenerationOnly() {
        given(receipts.findByDraftIdAndGenerationNo(10, 3)).willReturn(Optional.empty());
        assertThat(processor.replay(new ProductDraftGenerationRegisterCommand(10, 3, HASH, 30, EXPIRY))).isEmpty();
    }

    @Test
    void requestKeepsThePayloadContractAndPutsTheGenerationInTheDedupKey() {
        var request = processor.request(new ProductDraftGenerationRegisterCommand(10, 2, HASH, 30, EXPIRY));
        assertThat(request.type()).isEqualTo(ProductDraftGenerationJobProcessor.TYPE);
        assertThat(request.payloadJson()).isEqualTo("{\"draftId\":10}");
        assertThat(request.dedupKey()).isEqualTo("product-draft:10:2");
        assertThat(request.requestedByMemberId()).isEqualTo(30L);
    }

    @Test
    void recordWritesMicrosecondsBeforePersistenceCanRoundNanosUp() {
        var command = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 30, EXPIRY);
        var request = processor.request(command);
        var job = Job.register(20, request.type(), request.payloadJson(), request.dedupKey(), 30L, 0, EXPIRY.minusSeconds(60), 2);
        assertThat(processor.record(command, new JobRegistration(job, true)).jobId()).isEqualTo(20);
        var captor = ArgumentCaptor.forClass(ProductDraftGenerationReceipt.class);
        verify(receipts).insert(captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(Instant.parse("2026-11-07T00:01:00.123456Z"));
        assertThat(captor.getValue().generationNo()).isEqualTo(1);
    }

    @Test
    void recordRejectsANewJobBoundToAnotherDraft() {
        var command = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 30, EXPIRY);
        var job = Job.register(20, ProductDraftGenerationJobProcessor.TYPE, "{\"draftId\":11}", null, 30L, 0, EXPIRY.minusSeconds(60), 2);
        assertError(() -> processor.record(command, new JobRegistration(job, true)), ProductDraftGenerationErrorCode.INVALID_RESULT);
        verifyNoInteractions(receipts);
    }

    @Test
    void existingActiveJobOfAnotherActorIsALostRaceAndNeverGetsThisReceipt() {
        var command = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 31, EXPIRY);
        var job = Job.register(20, ProductDraftGenerationJobProcessor.TYPE, "{\"draftId\":10}", "product-draft:10:1", 30L, 0, EXPIRY.minusSeconds(60), 2);
        assertThatThrownBy(() -> processor.record(command, new JobRegistration(job, false)))
            .isInstanceOf(ProductDraftGenerationRegistrationRaceException.class);
        verifyNoInteractions(receipts);
    }

    @Test
    void nullExpiryStillUsesTheDomainValidationError() {
        assertError(() -> processor.request(new ProductDraftGenerationRegisterCommand(10, 1, HASH, 30, null)), ProductDraftGenerationErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, -1})
    void generationOutsideOneToThreeIsInvalid(int generationNo) {
        assertError(() -> processor.request(new ProductDraftGenerationRegisterCommand(10, generationNo, HASH, 30, EXPIRY)),
            ProductDraftGenerationErrorCode.INVALID_REQUEST);
    }

    @Test
    void expiryAtOrBeforeNowIsExpired() {
        var atNow = new ProductDraftGenerationRegisterCommand(10, 1, HASH, 30, EXPIRY.minusSeconds(60));
        assertError(() -> processor.request(atNow), ProductDraftGenerationErrorCode.EXPIRED);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ProductDraftGenerationErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftGenerationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
