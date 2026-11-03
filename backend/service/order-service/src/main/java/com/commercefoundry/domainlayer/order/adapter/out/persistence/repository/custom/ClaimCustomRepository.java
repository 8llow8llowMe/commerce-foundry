package com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.order.application.port.out.query.ClaimAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ClaimCustomRepository {
    Page<ClaimAdminSummaryQueryResult> findAdminSummaryPage(Long orderId, Claim.Type type, Claim.Status status, Pageable pageable);
}
