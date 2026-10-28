package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim.Outcome;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 조회 후 경합할 수 있으므로 획득은 유니크 키·버전 조건으로 확정한다. 외부 트랜잭션을 받지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class OrderRequestProcessor {
    private final OrderRequestRepositoryPort repository;
    private final SnowflakeIdGenerator ids;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderRequestClaim check(long memberId, UUID key, String hash) {
        validate(memberId, key, hash);
        return repository.findByMemberIdAndIdempotencyKey(memberId, key).map(request -> decide(request, hash)).orElseGet(OrderRequestClaim::none);
    }

    public OrderRequestClaim acquire(long memberId, UUID key, String hash, OrderRequestClaim checked) {
        validate(memberId, key, hash);
        if (checked == null || (checked.outcome() != Outcome.NONE && checked.outcome() != Outcome.TAKE_OVER)) {
            throw new IllegalArgumentException("Only NONE or TAKE_OVER can be acquired");
        }
        if (checked.request() != null && (checked.request().memberId() != memberId || !checked.request().idempotencyKey().equals(key)
            || !checked.request().matches(hash))) {
            throw new IllegalArgumentException("The claim does not match this request");
        }
        Optional<OrderRequest> acquired = checked.outcome() == Outcome.NONE
            ? repository.insertIfAbsent(OrderRequest.start(ids.generateId(), memberId, key, hash, ids.generateId(), clock.instant(), properties.creation().processingLease()))
            : repository.takeOver(checked.request(), clock.instant(), properties.creation().processingLease());
        if (acquired.isPresent()) {
            return new OrderRequestClaim(Outcome.ACQUIRED, acquired.get());
        }
        OrderRequestClaim reread = check(memberId, key, hash);
        if (reread.isReplay()) {
            return reread;
        }
        throw new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    /** 실패한 주인만 자신의 기한을 끝낸다. 저장 장애는 원래 오류를 가리지 않고 고정 기한 만료로 복구한다. */
    public void endLeaseQuietly(OrderRequest owned) {
        try {
            repository.endLease(owned, clock.instant());
        } catch (RuntimeException failure) {
            log.warn("order request lease not ended. orderId={}, type={}", owned.orderId(), failure.getClass().getSimpleName());
        }
    }

    private OrderRequestClaim decide(OrderRequest request, String hash) {
        if (!request.matches(hash)) {
            throw new OrderException(OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (request.status() == OrderRequest.Status.COMPLETED) {
            return new OrderRequestClaim(Outcome.REPLAY, request);
        }
        if (request.isLeasedAt(clock.instant())) {
            throw new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
        return new OrderRequestClaim(Outcome.TAKE_OVER, request);
    }

    private static void validate(long memberId, UUID key, String hash) {
        if (memberId <= 0 || key == null || hash == null || !hash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid request identity or digest");
        }
    }
}
