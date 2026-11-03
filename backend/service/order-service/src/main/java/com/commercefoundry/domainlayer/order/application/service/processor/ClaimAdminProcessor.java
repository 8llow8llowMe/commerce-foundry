package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminSummaryInfo;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimAdminQueryPort;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true, timeout = 5)
public class ClaimAdminProcessor {
    private final ClaimAdminQueryPort claims;

    public Page<ClaimAdminSummaryInfo> search(ClaimAdminFilter filter) {
        return claims.findAdminSummaryPage(filter.orderId(), filter.type() == null ? null : filter.type().toDomain(),
            filter.status() == null ? null : filter.status().toDomain(), filter.page(), filter.size()).map(ClaimAdminSummaryInfo::from);
    }

    public ClaimAdminDetailInfo getDetail(long claimId) {
        return ClaimAdminDetailInfo.from(claims.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM)));
    }
}
