package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStockRestorationRepository;
import com.commercefoundry.domainlayer.order.application.mapper.OrderStockRestorationMapper;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderStockRestorationRepositoryAdapter implements OrderStockRestorationRepositoryPort {

    private final OrderStockRestorationRepository orderStockRestorationRepository;
    private final OrderStockRestorationMapper orderStockRestorationMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderStockRestoration insert(OrderStockRestoration restoration) {
        if (!restoration.isNew()) {
            throw new IllegalArgumentException("이미 저장된 복원 작업입니다: id=" + restoration.id());
        }
        return orderStockRestorationMapper.toDomain(orderStockRestorationRepository.saveAndFlush(orderStockRestorationMapper.toEntity(restoration)));
    }

    @Override
    public boolean existsByClaimId(long claimId) {
        return orderStockRestorationRepository.existsByClaimId(claimId);
    }

    @Override
    public List<OrderStockRestoration> findDueRestorations(Instant now, int limit) {
        return orderStockRestorationRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(Status.RESTORE_PENDING, now, Limit.of(limit))
            .stream().map(orderStockRestorationMapper::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(OrderStockRestoration before, OrderStockRestoration claimed, Instant now) {
        requireSameRow(before, claimed);
        if (before.status() != Status.RESTORE_PENDING || claimed.status() != Status.RESTORE_PENDING
            || claimed.attempts() != Math.addExact(before.attempts(), 1) || !claimed.nextAttemptAt().isAfter(before.nextAttemptAt())) {
            throw new IllegalArgumentException("pending restoration with incremented attempt and later lease required");
        }
        return orderStockRestorationRepository.claim(before.id(), before.status(), before.nextAttemptAt(), claimed.attempts(), claimed.nextAttemptAt(),
            micros(now)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hold(OrderStockRestoration claimed, OrderStockRestoration held, Instant now) {
        requireSameRow(claimed, held);
        if (claimed.status() != Status.RESTORE_PENDING || held.status() != Status.HELD || claimed.attempts() != held.attempts()) {
            throw new IllegalArgumentException("held restoration required: status=" + held.status());
        }
        return orderStockRestorationRepository.hold(claimed.id(), claimed.status(), claimed.nextAttemptAt(), Status.HELD, held.holdCode(), micros(now)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markRestored(long claimId, Instant restoredAt) {
        return orderStockRestorationRepository.markRestored(claimId, EnumSet.of(Status.RESTORE_PENDING, Status.HELD), Status.RESTORED, micros(restoredAt)) == 1;
    }

    private static void requireSameRow(OrderStockRestoration before, OrderStockRestoration after) {
        if (before.id() != after.id() || before.claimId() != after.claimId() || before.orderId() != after.orderId()
            || before.paymentId() != after.paymentId() || before.stockReservationId() != after.stockReservationId() || before.origin() != after.origin()) {
            throw new IllegalArgumentException("same restoration row required: id=" + before.id());
        }
    }

    private static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
