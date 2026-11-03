package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.ClaimItemRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.ClaimRepository;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.custom.ClaimCustomRepository;
import com.commercefoundry.domainlayer.order.application.mapper.ClaimMapper;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimAdminQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.ClaimAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** QueryDSL을 필요로 하는 관리자 읽기를 일반 접수 저장소의 구성과 분리한다. */
@Component
@RequiredArgsConstructor
public class ClaimAdminQueryAdapter implements ClaimAdminQueryPort {
    private final ClaimCustomRepository queries;
    private final ClaimRepository claims;
    private final ClaimItemRepository items;
    private final ClaimMapper mapper;

    @Override
    public Page<ClaimAdminSummaryQueryResult> findAdminSummaryPage(Long orderId, Claim.Type type, Claim.Status status, int page, int size) {
        return queries.findAdminSummaryPage(orderId, type, status, PageRequest.of(page, size));
    }

    @Override
    public Optional<Claim> findById(long claimId) {
        return claims.findById(claimId).map(entity -> mapper.toDomain(entity, items.findAllByClaimIdOrderByOrderItemIdAsc(claimId)));
    }
}
