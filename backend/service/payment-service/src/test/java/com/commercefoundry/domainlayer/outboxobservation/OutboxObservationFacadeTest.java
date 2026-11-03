package com.commercefoundry.domainlayer.outboxobservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.presenter.OutboxObservationInternalPresenter;
import com.commercefoundry.domainlayer.outboxobservation.application.exception.OutboxObservationException;
import com.commercefoundry.domainlayer.outboxobservation.application.service.OutboxObservationInternalFacade;
import com.commercefoundry.domainlayer.outboxobservation.application.service.processor.OutboxObservationProcessor;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@ExtendWith(OutputCaptureExtension.class)
class OutboxObservationFacadeTest {

    static Stream<RuntimeException> failures() {
        return Stream.of(new QueryTimeoutException("sentinel-db-secret"),
            new CannotCreateTransactionException("sentinel-begin-secret"), new TransactionSystemException("sentinel-commit-secret"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void sanitizesQueryAndTransactionBoundaryFailures(RuntimeException failure, CapturedOutput output) {
        var processor = mock(OutboxObservationProcessor.class);
        when(processor.observeReady()).thenThrow(failure);
        var facade = new OutboxObservationInternalFacade(processor, new OutboxObservationInternalPresenter());

        Throwable result = catchThrowable(facade::observeReady);

        assertThat(result).isInstanceOf(OutboxObservationException.class).hasNoCause();
        assertThat(result.getMessage()).doesNotContain("sentinel");
        assertThat(((OutboxObservationException) result).getErrorCode().getCode()).isEqualTo("OUTBOX_OBSERVATION_001");
        assertThat(output.getAll()).contains("OUTBOX_OBSERVATION_001", failure.getClass().getSimpleName()).doesNotContain("sentinel");
    }
}
