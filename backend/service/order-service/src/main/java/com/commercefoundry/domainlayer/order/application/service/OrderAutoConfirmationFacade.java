package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.application.model.OrderConfirmationCandidate;
import com.commercefoundry.domainlayer.order.application.port.in.OrderAutoConfirmationUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAutoConfirmationProcessor;
import com.commercefoundry.global.properties.OrderAutoConfirmationProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 자동 구매 확정 스윕. 트랜잭션 밖에서 키셋을 읽고 각 주문은 별도 트랜잭션으로 처리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAutoConfirmationFacade implements OrderAutoConfirmationUseCase {

    private final OrderAutoConfirmationProcessor processor;
    private final OrderAutoConfirmationProperties properties;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void confirmDeliveredOrders() {
        // 조회와 도메인 판정은 실행 시작에 고정한 DB 마이크로초 기준을 함께 쓴다.
        Instant cutoff = clock.instant().minus(properties.period()).truncatedTo(ChronoUnit.MICROS);
        int confirmed = 0;
        OrderConfirmationCandidate cursor = null;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            List<OrderConfirmationCandidate> candidates = processor.findDue(cutoff, cursor, properties.batchSize());
            for (OrderConfirmationCandidate candidate : candidates) {
                confirmed += confirmOne(candidate.orderId(), cutoff) ? 1 : 0;
            }
            if (candidates.size() < properties.batchSize()) {
                break;
            }
            cursor = candidates.getLast();
        }
        if (confirmed > 0) {
            log.info("orders auto-confirmed. count={}", confirmed);
        }
    }

    /** 실패한 건만 롤백하고 다음 건으로 간다. 로그는 원문 메시지 없이 타입 · 스택만 남긴다. */
    private boolean confirmOne(long orderId, Instant cutoff) {
        try {
            return processor.confirm(orderId, cutoff);
        } catch (OptimisticLockingFailureException exception) {
            log.warn("order auto-confirm conflicted. orderId={}, error={}", orderId, exception.getClass().getSimpleName());
            return false;
        } catch (RuntimeException exception) {
            log.error("order auto-confirm failed. orderId={}, error={}, rootCause={}", orderId, exception.getClass().getSimpleName(),
                NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName(), RedactedThrowable.of(exception));
            return false;
        }
    }
}
