package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import com.commercefoundry.domainlayer.order.application.port.in.OrderSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 기한이 지난 결제 대기 주문을 만료한다 ({@code order-flow-guide.md} §1-3). 주기 · 배치 크기는 {@code order.payment-expiry.sweep.*} 다.
 * 배치 · 커서 · 건별 트랜잭션은 {@code OrderSchedulerFacade} 가 맡고, 이 클래스는 주기만 건다.
 *
 * <p>인스턴스가 여럿이어도 분산 락(ShedLock)을 두지 않는다. 같은 주문을 두 인스턴스가 동시에 만료하면 {@code @Version} 이 하나만 통과시키고,
 * 진 쪽은 이력 · outbox 까지 롤백돼 {@code order-expired} 가 두 번 나가지 않는다 (product {@code StockReservationExpiryScheduler} 와 같다).
 * {@code enabled=false} 면 빈을 만들지 않는다 — test 프로파일이 끈다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "order.payment-expiry.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryScheduler {

    private final OrderSchedulerUseCase orderSchedulerUseCase;

    @Scheduled(
        initialDelayString = "${order.payment-expiry.sweep.interval:30s}",
        fixedDelayString = "${order.payment-expiry.sweep.interval:30s}"
    )
    public void expireOverdueOrders() {
        orderSchedulerUseCase.expireOverdueOrders();
    }
}
