package com.commercefoundry.domainlayer.paymentobservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.presenter.PaymentObservationInternalPresenter;
import com.commercefoundry.domainlayer.paymentobservation.application.exception.PaymentObservationException;
import com.commercefoundry.domainlayer.paymentobservation.application.service.PaymentObservationInternalFacade;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAnomalyObservationProcessor;
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
class PaymentObservationFacadeTest {

    static Stream<RuntimeException> failures() {
        return Stream.of(new QueryTimeoutException("sentinel-db-secret"),
            new CannotCreateTransactionException("sentinel-begin-secret"), new TransactionSystemException("sentinel-commit-secret"));
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
