package com.commercefoundry.domainlayer.payment.adapter.out.observation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentAutoCancelRepository;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAutoCancel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(OutputCaptureExtension.class)
class PaymentAutoCancelReviewObservationTest {
    @Test
    void gaugeQueryHasBothReadOnlyTransactionAndJpaTimeouts() throws Exception {
        var query = PaymentAutoCancelRepository.class.getMethod("countByState", PaymentAutoCancel.State.class);
        assertThat(query.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(query.getAnnotation(Transactional.class).timeout()).isEqualTo(2);
        assertThat(query.getAnnotation(QueryHints.class).value()).anySatisfy(hint -> {
            assertThat(hint.name()).isEqualTo("jakarta.persistence.query.timeout");
            assertThat(hint.value()).isEqualTo("2000");
        });
    }
    @Test
    void timeoutIsNanWithoutZeroOrStaleCacheAndWithoutRawFailureLog(CapturedOutput output) {
        var repository = mock(PaymentAutoCancelRepository.class);
        when(repository.countByState(PaymentAutoCancel.State.MANUAL_REVIEW))
            .thenReturn(2L).thenThrow(new QueryTimeoutException("SENTINEL_PRIVATE_DATABASE")).thenReturn(1L);
        var registry = new SimpleMeterRegistry();
        try {
            var beans = new StaticListableBeanFactory(Map.of("registry", registry));
            new PaymentAutoCancelReviewObservation(repository, beans.getBeanProvider(MeterRegistry.class));
            var gauge = registry.get(PaymentAutoCancelReviewObservation.GAUGE).gauge();
            assertThat(gauge.value()).isEqualTo(2);
            assertThat(gauge.value()).isNaN();
            assertThat(gauge.value()).isEqualTo(1);
            assertThat(gauge.getId().getTags()).isEmpty();
            assertThat(output).doesNotContain("SENTINEL_PRIVATE_DATABASE");
        } finally { registry.close(); }
    }
    @Test
    void noCounterBeforeCommitAndFixedCodeOnlyAfterCallback(CapturedOutput output) {
        var repository = mock(PaymentAutoCancelRepository.class);
        var registry = new SimpleMeterRegistry();
        try {
            var beans = new StaticListableBeanFactory(Map.of("registry", registry));
            var observation = new PaymentAutoCancelReviewObservation(repository, beans.getBeanProvider(MeterRegistry.class));
            observation.afterCommit(); // No active transaction must never emit a precommit event.
            assertThat(registry.find(PaymentAutoCancelReviewObservation.COUNTER).counter()).isNull();
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                observation.afterCommit();
                assertThat(registry.find(PaymentAutoCancelReviewObservation.COUNTER).counter()).isNull();
                TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
                assertThat(registry.get(PaymentAutoCancelReviewObservation.COUNTER).counter().count()).isEqualTo(1);
                assertThat(registry.get(PaymentAutoCancelReviewObservation.COUNTER).counter().getId().getTags()).isEmpty();
                assertThat(output).contains("AUTO_CANCEL_MANUAL_REVIEW");
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        } finally { registry.close(); }
    }
}
