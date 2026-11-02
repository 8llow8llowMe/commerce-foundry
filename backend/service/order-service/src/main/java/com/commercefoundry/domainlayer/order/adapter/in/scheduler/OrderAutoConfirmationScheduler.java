package com.commercefoundry.domainlayer.order.adapter.in.scheduler;

import com.commercefoundry.domainlayer.order.application.port.in.OrderAutoConfirmationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 배송 완료 기간이 지난 주문의 자동 구매 확정. 인스턴스 간 중복 실행은 주문 @Version 으로 보호한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "order.purchase-confirmation.auto.enabled", havingValue = "true", matchIfMissing = true)
public class OrderAutoConfirmationScheduler {

    private final OrderAutoConfirmationUseCase useCase;

    @Scheduled(
        initialDelayString = "${order.purchase-confirmation.auto.interval:1h}",
        fixedDelayString = "${order.purchase-confirmation.auto.interval:1h}"
    )
    public void confirmDeliveredOrders() {
        useCase.confirmDeliveredOrders();
    }
}
