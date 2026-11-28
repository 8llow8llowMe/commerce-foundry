package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.PaymentRestorationObservationInternalPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentRestorationObservationQueryResult;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentRestorationObservationProcessor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class PaymentRestorationObservationInternalFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-29T01:00:00.123456789Z");
    private final OrderStockRestorationRepositoryPort port = mock(OrderStockRestorationRepositoryPort.class);
    private final PaymentRestorationObservationInternalFacade facade = new PaymentRestorationObservationInternalFacade(
        new PaymentRestorationObservationProcessor(port, Clock.fixed(NOW, ZoneOffset.UTC)), new PaymentRestorationObservationInternalPresenter());

    @Test
    void usesOneMicrosecondCutoffForQueryAndResponse() {
        Instant cutoff = Instant.parse("2026-11-29T01:00:00.123456Z");
        when(port.observe(cutoff)).thenReturn(new PaymentRestorationObservationQueryResult(2, cutoff.minusSeconds(60), 1, cutoff.minusSeconds(120)));

        var response = facade.observe();

        assertThat(response.observedAt()).isEqualTo(cutoff);
        assertThat(response.pendingCount()).isEqualTo(2);
        assertThat(response.oldestPendingCreatedAt()).isEqualTo(cutoff.minusSeconds(60));
        assertThat(response.heldCount()).isEqualTo(1);
        assertThat(response.oldestHeldCreatedAt()).isEqualTo(cutoff.minusSeconds(120));
    }

    @Test
    void dataAccessOrTransactionFailureBecomesUnavailableWithoutCause() {
        when(port.observe(any())).thenThrow(new DataAccessResourceFailureException("PRIVATE db detail"));
        assertThatThrownBy(facade::observe).isInstanceOfSatisfying(OrderException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PAYMENT_RESTORATION_OBSERVATION_UNAVAILABLE);
            assertThat(exception).hasNoCause().hasMessageNotContaining("PRIVATE");
        });

        when(port.observe(any())).thenThrow(new CannotCreateTransactionException("PRIVATE tx detail"));
        assertThatThrownBy(facade::observe).isInstanceOf(OrderException.class).hasNoCause();
    }
}
