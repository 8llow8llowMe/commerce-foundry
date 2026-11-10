package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.ClaimItemEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.ClaimItemRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.ClaimRepository;
import com.commercefoundry.domainlayer.order.application.exception.ClaimRequestKeyConflictException;
import com.commercefoundry.domainlayer.order.application.mapper.ClaimMapper;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.RequestedReturnQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import com.commercefoundry.persistence.util.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ClaimRepositoryAdapter implements ClaimRepositoryPort {
    static final String REQUEST_KEY_CONSTRAINT = "uk_claim_order_id_request_key";

    private final ClaimRepository claimRepository;
    private final ClaimItemRepository claimItemRepository;
    private final ClaimMapper claimMapper;
    private final EntityManager entityManager;

    @Override
    public Optional<Claim> findByOrderIdAndRequestKey(long orderId, UUID requestKey) {
        return claimRepository.findByOrderIdAndRequestKey(orderId, requestKey)
            .map(entity -> claimMapper.toDomain(entity, claimItemRepository.findAllByClaimIdOrderByOrderItemIdAsc(entity.getId())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Claim> findByIdForUpdate(long claimId) {
        return claimRepository.findById(claimId).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0));
            return claimMapper.toDomain(entity, claimItemRepository.findAllByClaimIdOrderByOrderItemIdAsc(claimId));
        });
    }

    @Override
    public Map<Long, Long> sumRequestedReturnQuantities(long orderId) {
        return claimRepository.sumQuantities(orderId, Claim.Type.RETURN, Claim.Status.REQUESTED).stream()
            .collect(Collectors.toMap(RequestedReturnQuantityQueryResult::orderItemId, RequestedReturnQuantityQueryResult::quantity));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim insert(Claim claim) {
        if (claim.version() != null || claim.requestedAt() != null) {
            throw new IllegalArgumentException("Only new claims can be inserted");
        }
        ClaimEntity entity;
        List<ClaimItemEntity> items;
        try {
            entity = claimRepository.save(claimMapper.toEntity(claim));
            items = claimItemRepository.saveAll(claimMapper.toItemEntities(claim));
            claimRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            // 같은 주문 · 같은 요청 키만 사유로 가른다(운영 PostgreSQL 기준 — H2 는 제약 이름을 싣지 않아 원래 예외 그대로다). 그 밖의 위반은 그대로 던진다
            if (ConstraintViolations.violates(exception, REQUEST_KEY_CONSTRAINT)) {
                throw new ClaimRequestKeyConflictException();
            }
            throw exception;
        }
        // DB의 timestamp(6) 정밀도를 최초 응답에도 반영하여 같은 키 재생의 시각을 고정한다.
        entityManager.refresh(entity);
        return claimMapper.toDomain(entity, items);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim update(Claim claim) {
        if (claim.isNew()) {
            throw new IllegalArgumentException("Only stored claims can be updated");
        }
        // merge 가 버전을 대조한다 — 다른 트랜잭션이 먼저 바꿨으면 flush 에서 OptimisticLockingFailureException 이다.
        // 같은 트랜잭션에서 엔티티를 다시 읽어 버전을 견주지 않는다 — 1차 캐시의 값이 나와 늘 같아 보인다(OrderRepositoryAdapter#saveExpiry 와 같다)
        ClaimEntity saved = claimRepository.saveAndFlush(claimMapper.toEntity(claim));
        // 쓰기 매핑은 생성 시각을 싣지 않아 merge 결과의 생성 시각을 믿지 않는다 — 읽어 온 접수 시각과 항목은 그대로, 버전만 올린다
        return new Claim(claim.id(), claim.orderId(), claim.requestKey(), claim.type(), claim.status(), claim.expectedRefund(), claim.paymentId(),
            claim.attemptCount(), claim.nextAttemptAt(), claim.failureCode(), claim.decision(), claim.items(), claim.requestedAt(), saved.getVersion());
    }

    @Override
    public List<Claim> findCancellationRetryTargets(Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("positive limit required");
        }
        List<ClaimEntity> claims = claimRepository.findRetryTargets(Claim.Type.CANCELLATION, EnumSet.of(Claim.Status.REQUESTED, Claim.Status.REFUNDED), now,
            Limit.of(limit));
        if (claims.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ClaimItemEntity>> items = claimItemRepository.findAllByClaimIdInOrderByClaimIdAscOrderItemIdAsc(claims.stream().map(ClaimEntity::getId).toList())
            .stream().collect(Collectors.groupingBy(ClaimItemEntity::getClaimId));
        return claims.stream().map(entity -> claimMapper.toDomain(entity, items.getOrDefault(entity.getId(), List.of()))).toList();
    }
}
