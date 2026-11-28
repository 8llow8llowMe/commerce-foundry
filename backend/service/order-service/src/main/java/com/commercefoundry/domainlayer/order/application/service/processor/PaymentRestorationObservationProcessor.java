package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.info.PaymentRestorationObservationInfo;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentRestorationObservationQueryResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 결제 반영 재고 복원 작업 관측 (#808, {@code order-flow-guide.md} §3-6). 한 문장 · 한 스냅숏이다. */
@Component
@RequiredArgsConstructor
public class PaymentRestorationObservationProcessor {

    private final OrderStockRestorationRepositoryPort orderStockRestorationRepositoryPort;
    private final Clock clock;

    @Transactional(readOnly = true, timeout = 2)
    public PaymentRestorationObservationInfo observe() {
        // PostgreSQL timestamp 정밀도는 마이크로초다. 같은 기준 시각을 쿼리와 응답에 함께 쓴다.
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        PaymentRestorationObservationQueryResult result = orderStockRestorationRepositoryPort.observe(observedAt);
        return new PaymentRestorationObservationInfo(observedAt, result.pendingCount(), result.oldestPendingCreatedAt(), result.heldCount(),
            result.oldestHeldCreatedAt());
    }
}
