package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRequestRepository;
import com.commercefoundry.domainlayer.order.application.mapper.OrderRequestMapper;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.global.persistence.ConstraintViolations;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 주문 요청 기록 저장. 리포지토리 호출마다 그 자리에서 flush 한다 — 바깥 트랜잭션이 없으면(기록 잡기) 리포지토리 호출 하나가 한 트랜잭션이고,
 * 있으면(주문 저장의 기록 완료) 그 트랜잭션에 들어간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRequestRepositoryAdapter implements OrderRequestRepositoryPort {

    /** {@code OrderRequestEntity} 의 {@code @UniqueConstraint} 이름과 같다. */
    static final String KEY_CONSTRAINT = "uk_order_request_member_id_idempotency_key";

    private final OrderRequestRepository orderRequestRepository;
    private final OrderRequestMapper orderRequestMapper;

    /** 같은 키 유니크 위반만 빈 값으로 바꾼다. 다른 제약 위반은 버그라 그대로 던진다. */
    @Override
    public Optional<OrderRequest> insertIfAbsent(OrderRequest request) {
        try {
            return Optional.of(orderRequestMapper.toDomain(orderRequestRepository.saveAndFlush(orderRequestMapper.toEntity(request))));
        } catch (DataIntegrityViolationException | PersistenceException exception) {
            if (ConstraintViolations.violates(exception, KEY_CONSTRAINT)) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public Optional<OrderRequest> findByMemberIdAndIdempotencyKey(long memberId, UUID idempotencyKey) {
        return orderRequestRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey).map(orderRequestMapper::toDomain);
    }

    @Override
    public Optional<OrderRequest> saveIfUnchanged(OrderRequest request) {
        try {
            return Optional.of(orderRequestMapper.toDomain(orderRequestRepository.saveAndFlush(orderRequestMapper.toEntity(request))));
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            log.info("order request concurrently modified. orderId={}", request.orderId());
            return Optional.empty();
        }
    }
}
