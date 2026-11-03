package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStockConfirmationEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStockConfirmationRepository;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockConfirmationRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderStockConfirmationRepositoryAdapter implements OrderStockConfirmationRepositoryPort {

    private final OrderStockConfirmationRepository orderStockConfirmationRepository;

    /**
     * PK 로 읽고 있으면 바꾸고(dirty checking) 없으면 넣는다 — 바로 flush 해 실패를 이 자리에서 드러낸다. 같은 결제의 동시 처리가 함께 넣으면 늦은 쪽이 PK 위반으로
     * 실패하고 호출 트랜잭션이 롤백된다 — 확정을 부르지 않고 재전달로 다시 한다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(long paymentId, long orderId, long stockReservationId, UUID eventId) {
        Optional<OrderStockConfirmationEntity> found = orderStockConfirmationRepository.findById(paymentId);
        if (found.isPresent()) {
            found.get().changeReservation(stockReservationId, eventId);
            orderStockConfirmationRepository.flush();
            return;
        }
        orderStockConfirmationRepository.saveAndFlush(OrderStockConfirmationEntity.builder()
            .paymentId(paymentId)
            .orderId(orderId)
            .stockReservationId(stockReservationId)
            .eventId(eventId)
            .build());
    }

    @Override
    public Optional<Long> findStockReservationId(long paymentId) {
        return orderStockConfirmationRepository.findById(paymentId).map(OrderStockConfirmationEntity::getStockReservationId);
    }
}
