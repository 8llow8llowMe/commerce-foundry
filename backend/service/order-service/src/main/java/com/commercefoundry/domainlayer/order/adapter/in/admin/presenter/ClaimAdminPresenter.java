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

@Component
public class ClaimAdminPresenter {
    public PageResponse<ClaimAdminItem> toPageResponse(Page<ClaimAdminSummaryInfo> page) {
        return PageResponse.of(page.map(row -> new ClaimAdminItem(String.valueOf(row.claimId()), String.valueOf(row.orderId()),
            row.type().toMetadata(), row.status().toMetadata(), MoneyItems.of(row.expectedRefund()), row.requestedAt())));
    }

    public ClaimAdminResponse toResponse(ClaimAdminDetailInfo claim) {
        return new ClaimAdminResponse(String.valueOf(claim.claimId()), String.valueOf(claim.orderId()), claim.type().toMetadata(), claim.status().toMetadata(),
            MoneyItems.of(claim.expectedRefund()), claim.requestedAt(), claim.items().stream()
                .map(item -> new ClaimAdminLineItem(String.valueOf(item.orderItemId()), item.quantity(), item.reason().toMetadata())).toList());
    }
}
