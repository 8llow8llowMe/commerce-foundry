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
    private final ClaimReturnDecisionProcessor returnDecision;

    public Page<ClaimAdminSummaryInfo> search(ClaimAdminFilter filter) {
        return claims.findAdminSummaryPage(filter.orderId(), filter.type() == null ? null : filter.type().toDomain(),
            filter.status() == null ? null : filter.status().toDomain(), filter.page(), filter.size()).map(ClaimAdminSummaryInfo::from);
    }

    /**
     * 상세 — 처리 버튼 표시 여부는 각 실행과 같은 조건({@link ClaimAdminRecoveryProcessor})으로 판정한다. 클레임만 읽는다(쿼리 2번, #17).
     * 예외: 결정 대기 반품({@link Claim#isAwaitingReturnDecision()})은 반품 결정 미리보기({@link ClaimReturnDecisionProcessor#preview})를 붙이며 주문을 읽고,
     * 주문이 반품 요청이면 같은 주문의 클레임 목록 · 결제 기록까지 읽는다(#17 B2c) — 항목 수와 무관한 고정 횟수다.
     */
    public ClaimAdminDetailInfo getDetail(long claimId) {
        Claim claim = claims.findById(claimId).orElseThrow(() -> new OrderException(OrderErrorCode.NOT_FOUND_CLAIM));
        return ClaimAdminDetailInfo.from(claim, recovery.isRetryable(claim), recovery.isRefundConfirmable(claim), recovery.isCompletable(claim),
            claim.isAwaitingReturnDecision() ? returnDecision.preview(claim) : null);
    }
}
