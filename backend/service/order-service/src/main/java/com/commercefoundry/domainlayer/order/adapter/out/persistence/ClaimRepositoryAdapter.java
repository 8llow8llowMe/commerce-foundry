package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.ClaimItemRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.ClaimRepository;
import com.commercefoundry.domainlayer.order.application.mapper.ClaimMapper;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.RequestedReturnQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ClaimRepositoryAdapter implements ClaimRepositoryPort {
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
        var entity = claimRepository.save(claimMapper.toEntity(claim));
        var items = claimItemRepository.saveAll(claimMapper.toItemEntities(claim));
        claimRepository.flush();
        // DB의 timestamp(6) 정밀도를 최초 응답에도 반영하여 같은 키 재생의 시각을 고정한다.
        entityManager.refresh(entity);
        return claimMapper.toDomain(entity, items);
    }
}
