package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderRequestRepository;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.mapper.OrderRequestMapper;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OrderRequestRepositoryAdapter implements OrderRequestRepositoryPort {
    private final OrderRequestRepository repository;
    private final OrderRequestMapper mapper;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public OrderRequestRepositoryAdapter(OrderRequestRepository repository, OrderRequestMapper mapper, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.mapper = mapper;
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setTimeout(5);
        read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setTimeout(5);
    }

    @Override
    public Optional<OrderRequest> insertIfAbsent(OrderRequest request) {
        if (request.version() != null || request.status() != OrderRequest.Status.PROCESSING) {
            throw new IllegalArgumentException("A new processing request is required");
        }
        try {
            return Optional.ofNullable(write.execute(status -> mapper.toDomain(repository.saveAndFlush(mapper.toEntity(request)))));
        } catch (DataIntegrityViolationException conflict) {
            // execute 밖이므로 실패한 트랜잭션은 이미 롤백됐다. 이후 조회는 새 트랜잭션이다.
            if (ConstraintViolations.violates(conflict, "uk_order_request_member_id_idempotency_key")) {
                return Optional.empty();
            }
            throw conflict;
        }
    }

    @Override
    public Optional<OrderRequest> findByMemberIdAndIdempotencyKey(long memberId, UUID key) {
        return read.execute(status -> repository.findByMemberIdAndIdempotencyKey(memberId, key).map(mapper::toDomain));
    }

    @Override
    public Optional<OrderRequest> takeOver(OrderRequest expected, Instant now, Duration lease) {
        OrderRequest next = expected.takeOver(now, lease);
        return write.execute(status -> repository.takeOver(expected, micros(now), next.leaseExpiresAt(), OrderRequest.Status.PROCESSING) == 1
            ? repository.findById(expected.id()).map(mapper::toDomain) : Optional.empty());
    }

    @Override
    public boolean endLease(OrderRequest expected, Instant now) {
        return Boolean.TRUE.equals(write.execute(status -> repository.endLease(expected, micros(now), OrderRequest.Status.PROCESSING) == 1));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeOwned(OrderRequest expected, Instant now) {
        if (repository.completeOwned(expected, micros(now), OrderRequest.Status.PROCESSING, OrderRequest.Status.COMPLETED) != 1) {
            throw new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
