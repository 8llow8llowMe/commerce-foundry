package com.commercefoundry.domainlayer.order.adapter.in.admin.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.ClaimAdminLineItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.ClaimAdminResponse;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminSummaryInfo;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** 어드민 클레임 응답 — 식별자(클레임 · 주문 · 결제 · 결정한 관리자)는 여기서 문자열로 바꾼다. */
@Component
public class ClaimAdminPresenter {
    public PageResponse<ClaimAdminItem> toPageResponse(Page<ClaimAdminSummaryInfo> page) {
        return PageResponse.of(page.map(row -> new ClaimAdminItem(String.valueOf(row.claimId()), String.valueOf(row.orderId()),
            row.type().toMetadata(), row.status().toMetadata(), MoneyItems.of(row.expectedRefund()), idOrNull(row.paymentId()), row.failureCode(),
            row.attemptCount(), row.nextAttemptAt(), row.decisionReason(), idOrNull(row.decidedByMemberId()), row.decidedAt(), row.requestedAt())));
    }

    public ClaimAdminResponse toResponse(ClaimAdminDetailInfo claim) {
        return new ClaimAdminResponse(String.valueOf(claim.claimId()), String.valueOf(claim.orderId()), claim.type().toMetadata(), claim.status().toMetadata(),
            MoneyItems.of(claim.expectedRefund()), idOrNull(claim.paymentId()), claim.failureCode(), claim.attemptCount(), claim.nextAttemptAt(),
            claim.decisionReason(), idOrNull(claim.decidedByMemberId()), claim.decidedAt(), claim.requestedAt(), claim.items().stream()
                .map(item -> new ClaimAdminLineItem(String.valueOf(item.orderItemId()), item.quantity(), item.reason().toMetadata())).toList());
    }

    private static String idOrNull(Long id) {
        return id == null ? null : String.valueOf(id);
    }
}
