package com.commercefoundry.domainlayer.outboxobservation.application.service;

import com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.dto.response.OutboxObservationResponse;
import com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.presenter.OutboxObservationInternalPresenter;
import com.commercefoundry.domainlayer.outboxobservation.application.exception.OutboxObservationErrorCode;
import com.commercefoundry.domainlayer.outboxobservation.application.exception.OutboxObservationException;
import com.commercefoundry.domainlayer.outboxobservation.application.port.in.OutboxObservationInternalUseCase;
import com.commercefoundry.domainlayer.outboxobservation.application.service.processor.OutboxObservationProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxObservationInternalFacade implements OutboxObservationInternalUseCase {

    private final OutboxObservationProcessor processor;
    private final OutboxObservationInternalPresenter presenter;

    @Override
    public OutboxObservationResponse observeReady() {
        // Stay outside the transaction so begin/commit failures from the entire processor proxy call are translated too.
        try {
            return presenter.toResponse(processor.observeReady());
        } catch (DataAccessException | TransactionException exception) {
            var errorCode = OutboxObservationErrorCode.OBSERVATION_UNAVAILABLE;
            log.warn("outbox observation failed. code={}, causeType={}", errorCode.getCode(), exception.getClass().getSimpleName());
            throw new OutboxObservationException(errorCode);
        }
    }
}
