package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStockConfirmationParticipantEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStockConfirmationParticipantEntity.Key;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.OrderStockRestorationRepository;
import com.commercefoundry.domainlayer.order.application.mapper.OrderStockRestorationMapper;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentRestorationObservationQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
    private final EntityManager entityManager;

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
    public Optional<OrderStockRestoration> findByClaimId(long claimId) {
        return orderStockRestorationRepository.findByClaimId(claimId).map(orderStockRestorationMapper::toDomain);
    }

    @Override
    public List<OrderStockRestoration> findDueRestorations(Instant now, int limit) {
        return orderStockRestorationRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(Status.RESTORE_PENDING,
            now, Limit.of(limit))
            .stream().map(orderStockRestorationMapper::toDomain).toList();
    }

    @Override
    public Optional<OrderStockRestoration> findByReservationId(long reservationId) {
        return orderStockRestorationRepository.findByStockReservationId(reservationId).map(orderStockRestorationMapper::toDomain);
    }

    @Override
    public long countByStatus(Status status) {
        return orderStockRestorationRepository.countByStatus(status);
    }

    @Override
    public Optional<Instant> findOldestCreatedAt(Status status) {
        return Optional.ofNullable(orderStockRestorationRepository.findOldestCreatedAt(status));
    }

    @Override
    public PaymentRestorationObservationQueryResult observe(Instant observedAt) {
        OrderStockRestorationRepository.ObservationRow row = orderStockRestorationRepository.observe(Status.RESTORE_PENDING, Status.HELD, observedAt);
        return new PaymentRestorationObservationQueryResult(row.getPendingCount(), row.getOldestPendingCreatedAt(), row.getHeldCount(),
            row.getOldestHeldCreatedAt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addConfirmationParticipant(long workId, long paymentId) {
        Key key = new Key(workId, paymentId);
        if (entityManager.find(OrderStockConfirmationParticipantEntity.class, key) == null) {
            entityManager.persist(new OrderStockConfirmationParticipantEntity(key));
        }
    }

    @Override
    public boolean hasResolvedParticipant(long workId, Order order) {
        var amount = order.quote().totalAmount();
        return orderStockRestorationRepository.hasResolvedParticipant(workId, order.id(), order.memberId(), amount.amount(), amount.currency());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markAbandoned(OrderStockRestoration work, Instant now) {
        boolean marked = orderStockRestorationRepository.markAbandoned(work.id(), work.origin(), work.abandonConfirmation().origin(),
            work.status(), work.nextAttemptAt(), micros(now)) == 1;
        if (marked) {
            orderStockRestorationRepository.deleteParticipants(work.id());
        }
        return marked;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean delete(OrderStockRestoration work) {
        boolean deleted = orderStockRestorationRepository.deleteByIdAndOrigin(work.id(), work.origin()) == 1;
        if (deleted) {
            orderStockRestorationRepository.deleteParticipants(work.id());
        }
        return deleted;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(OrderStockRestoration before, OrderStockRestoration claimed, Instant now) {
        requireSameRow(before, claimed);
        if (before.status() != Status.RESTORE_PENDING || claimed.status() != Status.RESTORE_PENDING
            || claimed.attempts() != Math.addExact(before.attempts(), 1) || !claimed.nextAttemptAt().isAfter(before.nextAttemptAt())) {
            throw new IllegalArgumentException("pending restoration with incremented attempt and later lease required");
        }
        return orderStockRestorationRepository.claim(before.id(), before.origin(), before.status(), before.nextAttemptAt(), claimed.attempts(), claimed.nextAttemptAt(),
            micros(now)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hold(OrderStockRestoration claimed, OrderStockRestoration held, Instant now) {
        requireSameRow(claimed, held);
        if (claimed.status() != Status.RESTORE_PENDING || held.status() != Status.HELD || claimed.attempts() != held.attempts()) {
            throw new IllegalArgumentException("held restoration required: status=" + held.status());
        }
        return orderStockRestorationRepository.hold(claimed.id(), claimed.origin(), claimed.status(), claimed.nextAttemptAt(), Status.HELD, held.holdCode(), micros(now)) == 1;
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
