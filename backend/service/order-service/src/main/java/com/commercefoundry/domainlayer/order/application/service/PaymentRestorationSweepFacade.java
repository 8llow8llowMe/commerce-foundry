package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationSweepUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentRestorationSweepProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentRestorationSweepProcessor.Hold;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Origin;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRestorationSweepFacade implements PaymentRestorationSweepUseCase {

    private final PaymentRestorationSweepProcessor processor;
    private final Clock clock;

    /** 선점과 완료만 별도 커밋하며 원격 호출 동안 DB 트랜잭션을 열지 않는다. */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void sweep(int batchSize, Duration initialBackoff, Duration maxBackoff) {
        if (batchSize < 1 || batchSize > 10 || initialBackoff.compareTo(Duration.ofMinutes(1)) < 0
            || maxBackoff.compareTo(initialBackoff) < 0 || maxBackoff.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("invalid payment restoration batch limits");
        }
        for (OrderStockRestoration due : processor.findDue(clock.instant(), batchSize)) {
            try {
                process(due, initialBackoff, maxBackoff);
            } catch (RuntimeException exception) {
                // 커밋 실패도 다른 행을 막지 않는다. 원격 응답과 예외 메시지는 로그에 남기지 않는다.
                log.warn("payment restoration attempt failed. workId={}, failure={}", due.id(), exception.getClass().getSimpleName());
            }
        }
    }

    private void process(OrderStockRestoration due, Duration initialBackoff, Duration maxBackoff) {
        Instant now = clock.instant();
        OrderStockRestoration claimed = due.claimAttempt(now, initialBackoff, maxBackoff);
        if (!processor.claim(due, claimed, now)) {
            return;
        }
        try {
            if (claimed.origin() == Origin.PAYMENT_RECORD) {
                processor.inspect(claimed).ifPresent(target -> {
                    boolean fullyCanceled = processor.restore(target);
                    processor.complete(claimed, fullyCanceled);
                });
            } else {
                processor.inspectConfirmation(claimed).ifPresent(order ->
                    processor.completeConfirmation(claimed, processor.restoreConfirmation(claimed, order)));
            }
        } catch (Hold exception) {
            if (processor.hold(claimed, exception.code())) {
                log.warn("payment restoration held. workId={}, code={}", claimed.id(), exception.code());
            }
        }
    }
}
