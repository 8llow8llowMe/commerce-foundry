package com.commercefoundry.domainlayer.order.adapter.in.admin.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminRecoveryItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminReturnApprovalItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminReturnDecisionItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminSummaryInfo;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class ClaimAdminPresenter {
    public PageResponse<ClaimAdminItem> toPageResponse(Page<ClaimAdminSummaryInfo> page) {
        return PageResponse.of(page.map(row -> new ClaimAdminItem(String.valueOf(row.claimId()), String.valueOf(row.orderId()),
            row.type().toMetadata(), row.status().toMetadata(), MoneyItems.of(row.expectedRefund()), row.requestedAt())));
    }

    public ClaimAdminResponse toResponse(ClaimAdminDetailInfo claim) {
        ClaimAdminDetailInfo.Recovery recovery = claim.recovery();
        ClaimAdminDetailInfo.LastDecision decision = recovery.lastDecision();
        return new ClaimAdminResponse(String.valueOf(claim.claimId()), String.valueOf(claim.orderId()), claim.type().toMetadata(), claim.status().toMetadata(),
            MoneyItems.of(claim.expectedRefund()), claim.requestedAt(), claim.items().stream()
                .map(item -> new ClaimAdminLineItem(String.valueOf(item.orderItemId()), item.quantity(), item.reason().toMetadata())).toList(),
            new ClaimAdminRecoveryItem(recovery.situation().toMetadata(), recovery.nextAutomaticAttemptAt(), recovery.retryable(), recovery.refundConfirmable(),
                recovery.completable(), decision == null ? null : new ClaimAdminRecoveryItem.DecisionItem(decision.reason(), decision.decidedAt()),
                recovery.version()),
            toApprovalItem(claim.returnApproval()), toDecisionItem(claim.returnDecision()));
    }

    private static ClaimAdminReturnApprovalItem toApprovalItem(ClaimAdminDetailInfo.ReturnApproval approval) {
        if (approval == null) {
            return null;
        }
        return new ClaimAdminReturnApprovalItem(approval.fault().toMetadata(), approval.restock(), MoneyItems.of(approval.deduction()), approval.approvedAt());
    }

    /** 정책 금액은 0 이하일 수 있어 Money 가 아니다 — 최소 단위 정수 + 통화 코드로 그대로 옮긴다. */
    private static ClaimAdminReturnDecisionItem toDecisionItem(ClaimAdminDetailInfo.ReturnDecision preview) {
        if (preview == null) {
            return null;
        }
        return new ClaimAdminReturnDecisionItem(preview.approvable(), preview.rejectable(), preview.blocker() == null ? null : preview.blocker().toMetadata(),
            preview.defaultFault() == null ? null : preview.defaultFault().toMetadata(),
            preview.refundByFault().stream().map(refund -> new ClaimAdminReturnDecisionItem.RefundItem(refund.fault().toMetadata(),
                MoneyItem.of(refund.policyAmount(), refund.currency().name()))).toList());
    }
}
