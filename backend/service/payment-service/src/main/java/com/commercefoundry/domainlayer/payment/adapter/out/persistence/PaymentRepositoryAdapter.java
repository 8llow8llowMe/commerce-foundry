package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapper;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 쓰기는 바로 flush 한다 — 유니크 키 위반 · 버전 충돌이 커밋 시점(트랜잭션 프록시 밖)이 아니라 이 호출에서 드러나 Processor 가 바꿀 수 있게
 * ({@code coding-conventions.md} §9-1).
 *
 * <p>업무로 다룰 충돌(활성 슬롯 · 결제키 중복 · 버전)은 포트 예외 {@link PaymentPersistenceConflictException} 하나로 감싼다 — application 이 spring-orm 타입에
 * 기대지 않게. 같은 버전 충돌이 confirm 에서는 "이미 처리 중", 재조회 · 웹훅에서는 "이미 반영됨" 으로 뜻이 달라 업무 오류 코드는 호출한 Processor 가 붙인다.
 */
@Component
@RequiredArgsConstructor
public class PaymentRepositoryAdapter implements PaymentRepositoryPort {

    private static final String ACTIVE_ORDER_CONSTRAINT = "uk_payment_active_order_id";
    private static final String PAYMENT_KEY_CONSTRAINT = "uk_payment_payment_key";

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    /**
     * 새 결제 시도(버전 null)는 persist, 읽어 온 결제 시도는 merge 다. 읽은 뒤 다른 트랜잭션이 커밋했으면 merge 의 버전 비교 또는 flush 의
     * {@code UPDATE ... where version = ?} 가 충돌로 끝난다.
     *
     * <p>제약 위반은 제약 이름의 정확 일치로 가린다({@link ConstraintViolations} — PostgreSQL 서버 오류 필드 우선, 메시지 문구로 가리지 않는다).
     * 활성 슬롯 · 결제키 유니크 키만 업무 충돌이고, 그 밖(check · NOT NULL · 다른 유니크 키)은 코드 오류라 제약 이름만 실어 다시 던진다 —
     * 어느 쪽이든 원인을 붙이지 않는다. 붙이면 DB 오류 메시지가 예외 메시지 · 스택으로 퍼진다(#173 2차 방어).
     */
    @Override
    public Payment save(Payment payment) {
        try {
            return paymentMapper.toDomain(paymentRepository.saveAndFlush(paymentMapper.toEntity(payment)));
        } catch (OptimisticLockingFailureException exception) {
            throw conflict(PaymentPersistenceConflictException.Kind.VERSION, payment);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, ACTIVE_ORDER_CONSTRAINT)) {
                throw conflict(PaymentPersistenceConflictException.Kind.ACTIVE_SLOT, payment);
            }
            if (ConstraintViolations.violates(exception, PAYMENT_KEY_CONSTRAINT)) {
                throw conflict(PaymentPersistenceConflictException.Kind.PAYMENT_KEY, payment);
            }
            String constraintName = ConstraintViolations.constraintNameOf(exception);
            throw new DataIntegrityViolationException("결제 시도 저장이 제약을 위반했습니다. paymentId=%d, constraint=%s".formatted(payment.id(), constraintName));
        }
    }

    @Override
    public Optional<Payment> findById(long paymentId) {
        return paymentRepository.findById(paymentId).map(paymentMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByPgOrderId(String pgOrderId) {
        return paymentRepository.findByPgOrderId(pgOrderId).map(paymentMapper::toDomain);
    }

    @Override
    public Optional<Payment> findActiveByOrderId(long orderId) {
        return paymentRepository.findByActiveOrderId(orderId).map(paymentMapper::toDomain);
    }

    private static PaymentPersistenceConflictException conflict(PaymentPersistenceConflictException.Kind kind, Payment payment) {
        return new PaymentPersistenceConflictException(kind, payment.id(), payment.orderId());
    }
}
