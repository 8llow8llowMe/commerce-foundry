package com.commercefoundry.domainlayer.payment.application.service;

import com.commercefoundry.domainlayer.payment.application.port.in.PaymentAutoCancelSchedulerUseCase;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelExecutionProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.AutoCancelScanProcessor;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentAutoCancelProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.AutoCancelLane;
import com.commercefoundry.global.properties.PaymentAutoCancelSchedulerProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Cursor reservation commits before each independently recoverable discovery/execution. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class PaymentAutoCancelSchedulerFacade implements PaymentAutoCancelSchedulerUseCase {
    private final AutoCancelScanProcessor scan;
    private final PaymentAutoCancelProcessor intents;
    private final PaymentAutoCancelExecutionProcessor executor;
    private final PaymentAutoCancelSchedulerProperties properties;
    private final Clock clock;

    @Override
    public void sweep() {
        if (!properties.enabled()) return;
        var cutoff = clock.instant();
        for (var lane : AutoCancelLane.values()) {
            try {
                for (int page = 0; page < properties.maxPagesPerLane(); page++) {
                    var ids = scan.reserveBatch(lane, cutoff, properties.pageSize());
                    if (ids.isEmpty()) break;
                    for (long id : ids) {
                        try {
                            if (lane == AutoCancelLane.PENDING) executor.execute(id);
                            else intents.enqueue(id);
                        } catch (RuntimeException failure) {
                            log.warn("Automatic cancellation scheduler: CANDIDATE_FAILED lane={}", lane);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("Automatic cancellation scheduler: LANE_FAILED lane={}", lane);
            }
        }
    }
}
