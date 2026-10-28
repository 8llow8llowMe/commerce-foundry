package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.application.command.OrderStockReleaseCommand;
import com.commercefoundry.domainlayer.product.application.model.StockReleaseOutcome;
import com.commercefoundry.domainlayer.product.application.port.in.StockEventUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.StockReservationProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 I/O 가 없어 Facade 에 트랜잭션을 건다 — 선점 상태와 재고 복원이 함께 커밋되거나 함께 롤백된다.
 * 리스너가 멱등 처리기({@code IdempotentEventProcessor})의 트랜잭션 안에서 부르므로 그 트랜잭션에 합류한다(REQUIRED) — 처리 기록(inbox)과 같은 커밋이다.
 */
@Service
@RequiredArgsConstructor
public class StockEventFacade implements StockEventUseCase {

    private final StockReservationProcessor stockReservationProcessor;

    @Override
    @Transactional
    public StockReleaseOutcome release(OrderStockReleaseCommand command) {
        return stockReservationProcessor.releaseByOrder(command.reservationId(), command.orderId(), command.reason());
    }
}
