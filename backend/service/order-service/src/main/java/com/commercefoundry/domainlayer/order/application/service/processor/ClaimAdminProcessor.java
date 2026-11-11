package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminSummaryInfo;
import com.commercefoundry.domainlayer.order.application.model.ClaimAdminFilter;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimAdminQueryPort;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true, timeout = 5)
public class ClaimAdminProcessor {
    private final ClaimAdminQueryPort claims;
    private final ClaimAdminRecoveryProcessor recovery;

    public Page<ClaimAdminSummaryInfo> search(ClaimAdminFilter filter) {
        return claims.findAdminSummaryPage(filter.orderId(), filter.type() == null ? null : filter.type().toDomain(),
            filter.status() == null ? null : filter.status().toDomain(), filter.page(), filter.size()).map(ClaimAdminSummaryInfo::from);
    }

    /** 상세 — 처리 버튼 표시 여부는 각 실행과 같은 조건({@link ClaimAdminRecoveryProcessor})으로 판정한다. 주문은 읽지 않는다(쿼리 2번 유지, #17). */
    public ClaimAdminDetailInfo getDetail(long claimId) {
        Claim claim = claims.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
        return ClaimAdminDetailInfo.from(claim, recovery.isRetryable(claim), recovery.isRefundConfirmable(claim), recovery.isCompletable(claim));
    }
}
