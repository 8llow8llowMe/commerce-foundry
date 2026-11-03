package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderPaymentEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderPaymentRepository;
import com.commercefoundry.domainlayer.order.application.exception.OrderPaymentSaveException;
import com.commercefoundry.domainlayer.order.application.mapper.OrderPaymentMapper;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.persistence.util.ConstraintViolations;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentRepositoryAdapter implements OrderPaymentRepositoryPort {

    static final String PAYMENT_ID_CONSTRAINT = "uk_order_payment_payment_id";
    static final String PAID_ORDER_ID_CONSTRAINT = "uk_order_payment_paid_order_id";
    /** inbox 유니크 키 — 같은 트랜잭션에 먼저 넣은 처리 기록이 이 자리 flush 에서 터지면 같은 이벤트의 정상 중복이다. */
    static final String PROCESSED_EVENT_CONSTRAINT = "uk_processed_event_consumer_group_event_id";

    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderPaymentMapper orderPaymentMapper;

    /**
     * 같은 트랜잭션의 앞선 쓰기를 먼저 flush 해 그 실패를 이 기록의 실패와 가른 뒤, {@code saveAndFlush} 로 바로 넣고(생성 시각 없음 → persist)
     * 위반한 제약 이름으로 사유를 가른다({@code ConstraintViolations}, 운영 PostgreSQL 기준 — H2 는 제약 이름을 싣지 않아 사유를 가르지 못한다).
     * 로그에는 주문 · 결제 아이디 · 예외 타입 · 사유만 남긴다.
     *
     * <ul>
     *   <li>앞선 flush 가 inbox 유니크 위반 — {@code EVENT_ALREADY_PROCESSED}(정상 중복, INFO). 그 밖의 앞선 쓰기 실패(주문 버전 충돌 등)는 원래 예외 그대로 던진다.</li>
     *   <li>기록 INSERT 가 결제 · 주문 유니크 위반 — {@code PAYMENT_ALREADY_RECORDED} · {@code ORDER_ALREADY_PAID}(INFO), 그 밖 — {@code REJECTED}(ERROR).</li>
     * </ul>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderPayment insert(OrderPayment orderPayment) {
        if (!orderPayment.isNew()) {
            throw new IllegalArgumentException("이미 저장된 결제 반영 기록입니다: id=" + orderPayment.id());
        }
        try {
            orderPaymentRepository.flush();
        } catch (DataAccessException | PersistenceException exception) {
            if (!ConstraintViolations.violates(exception, PROCESSED_EVENT_CONSTRAINT)) {
                throw exception;
            }
            throw saveException(orderPayment, OrderPaymentSaveException.Reason.EVENT_ALREADY_PROCESSED, exception);
        }
        OrderPaymentEntity saved;
        try {
            saved = orderPaymentRepository.saveAndFlush(orderPaymentMapper.toEntity(orderPayment));
        } catch (DataAccessException | PersistenceException exception) {
            OrderPaymentSaveException.Reason reason = ConstraintViolations.violates(exception, PAYMENT_ID_CONSTRAINT)
                ? OrderPaymentSaveException.Reason.PAYMENT_ALREADY_RECORDED
                : ConstraintViolations.violates(exception, PAID_ORDER_ID_CONSTRAINT)
                    ? OrderPaymentSaveException.Reason.ORDER_ALREADY_PAID
                    : OrderPaymentSaveException.Reason.REJECTED;
            throw saveException(orderPayment, reason, exception);
        }
        return orderPaymentMapper.toDomain(saved);
    }

    private static OrderPaymentSaveException saveException(OrderPayment orderPayment, OrderPaymentSaveException.Reason reason, RuntimeException exception) {
        String causeType = exception.getClass().getSimpleName();
        if (reason == OrderPaymentSaveException.Reason.REJECTED) {
            log.error("order payment save rejected. orderId={}, paymentId={}, type={}", orderPayment.orderId(), orderPayment.paymentId(), causeType);
        } else {
            log.info("order payment already handled. orderId={}, paymentId={}, reason={}", orderPayment.orderId(), orderPayment.paymentId(), reason);
        }
        return new OrderPaymentSaveException(reason, causeType);
    }

    @Override
    public Optional<OrderPayment> findByPaymentId(long paymentId) {
        return orderPaymentRepository.findByPaymentId(paymentId).map(orderPaymentMapper::toDomain);
    }

    @Override
    public Optional<OrderPayment> findPaidByOrderId(long orderId) {
        return orderPaymentRepository.findByPaidOrderId(orderId).map(orderPaymentMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markRestored(long id, Instant restoredAt) {
        return orderPaymentRepository.markRestored(id, restoredAt.truncatedTo(ChronoUnit.MICROS)) == 1;
    }
}
