package com.commercefoundry.domainlayer.paymentobservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.dto.response.PaymentObservationResponse;
import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.presenter.PaymentObservationInternalPresenter;
import com.commercefoundry.domainlayer.paymentobservation.application.exception.PaymentObservationException;
import com.commercefoundry.domainlayer.paymentobservation.application.service.PaymentObservationInternalFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAnomalyObservationProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@ExtendWith(OutputCaptureExtension.class)
class PaymentObservationFacadeTest {
    static final Instant OLDEST = Instant.parse("2026-11-03T01:02:03.123456Z");

    static Stream<RuntimeException> failures() {
        return Stream.of(new QueryTimeoutException("sentinel-db-secret"),
            new CannotCreateTransactionException("sentinel-begin-secret"), new TransactionSystemException("sentinel-commit-secret"),
            new DataIntegrityViolationException("sentinel-future-next-attempt"));
    }

    @Test
    void closedGateKeepsSeventhBucketNullInsteadOfZero() {
        var response = facade(observation(null)).observeAnomalies();
        assertThat(response.autoCancelManualReview()).isNull();
        assertThat(response.unknown()).isEqualTo(new PaymentObservationResponse.Bucket(1, OLDEST));
        assertThat(response.doneOrderUnsynced()).isEqualTo(new PaymentObservationResponse.Bucket(6, OLDEST.plusSeconds(5)));
    }

    @Test
    void openGateMapsSeventhBucketCountAndOldestAt() {
        var response = facade(observation(new PaymentAnomalyObservation.Bucket(0, null))).observeAnomalies();
        assertThat(response.autoCancelManualReview()).isEqualTo(new PaymentObservationResponse.Bucket(0, null));
        response = facade(observation(new PaymentAnomalyObservation.Bucket(3, OLDEST.minusSeconds(90)))).observeAnomalies();
        assertThat(response.autoCancelManualReview()).isEqualTo(new PaymentObservationResponse.Bucket(3, OLDEST.minusSeconds(90)));
        assertThat(response.observedAt()).isEqualTo(OLDEST.plusSeconds(60));
        assertThat(response.manualReviewUnknown()).isEqualTo(new PaymentObservationResponse.Bucket(2, OLDEST.plusSeconds(1)));
    }

    private static PaymentObservationInternalFacade facade(PaymentAnomalyObservation observation) {
        var processor = mock(PaymentAnomalyObservationProcessor.class);
        when(processor.observeAnomalies()).thenReturn(observation);
        return new PaymentObservationInternalFacade(processor, new PaymentObservationInternalPresenter());
    }

    private static PaymentAnomalyObservation observation(PaymentAnomalyObservation.Bucket autoCancel) {
        return new PaymentAnomalyObservation(OLDEST.plusSeconds(60),
            new PaymentAnomalyObservation.Bucket(1, OLDEST),
            new PaymentAnomalyObservation.Bucket(2, OLDEST.plusSeconds(1)),
            new PaymentAnomalyObservation.Bucket(3, OLDEST.plusSeconds(2)),
            new PaymentAnomalyObservation.Bucket(4, OLDEST.plusSeconds(3)),
            new PaymentAnomalyObservation.Bucket(5, OLDEST.plusSeconds(4)),
            new PaymentAnomalyObservation.Bucket(6, OLDEST.plusSeconds(5)),
            autoCancel);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void sanitizesQueryAndTransactionBoundaryFailures(RuntimeException failure, CapturedOutput output) {
        var processor = mock(PaymentAnomalyObservationProcessor.class);
        when(processor.observeAnomalies()).thenThrow(failure);
        var facade = new PaymentObservationInternalFacade(processor, new PaymentObservationInternalPresenter());

        Throwable result = catchThrowable(facade::observeAnomalies);

        assertThat(result).isInstanceOf(PaymentObservationException.class).hasNoCause();
        assertThat(result.getMessage()).doesNotContain("sentinel");
        assertThat(((PaymentObservationException) result).getErrorCode().getCode()).isEqualTo("PAYMENT_OBSERVATION_001");
        assertThat(output.getAll()).contains("PAYMENT_OBSERVATION_001", failure.getClass().getSimpleName()).doesNotContain("sentinel");
    }
}
