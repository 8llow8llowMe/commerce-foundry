package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.ClaimAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 관리자 조회를 고객 접수 저장소와 분리한다. */
public interface ClaimAdminQueryPort {
    Page<ClaimAdminSummaryQueryResult> findAdminSummaryPage(Long orderId, Claim.Type type, Claim.Status status, int page, int size);

    Optional<Claim> findById(long claimId);
}
