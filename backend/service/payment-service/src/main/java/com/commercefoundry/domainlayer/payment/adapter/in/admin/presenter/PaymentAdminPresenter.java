package com.commercefoundry.domainlayer.payment.adapter.in.admin.presenter;

import com.commercefoundry.commerce.support.web.MoneyItems;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.payment.adapter.in.admin.dto.item.PaymentAdminItem;
import com.commercefoundry.domainlayer.payment.application.info.PaymentAdminInfo;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class PaymentAdminPresenter {
    public PageResponse<PaymentAdminItem> toPageResponse(Page<PaymentAdminInfo> page) {
        return PageResponse.of(page).map(info -> new PaymentAdminItem(String.valueOf(info.paymentId()), String.valueOf(info.orderId()),
            info.status().toMetadata(), MoneyItems.of(info.amount()), info.method() == null ? null : info.method().toMetadata(),
            unknownReasonOf(info.unknownReason()), info.orderSynced(), info.orderSyncFailureCode(), info.createdAt(), info.updatedAt(),
            info.approvalStartedAt(), info.approvedAt()));
    }

    private static CodeNameDescriptionMetadata unknownReasonOf(PaymentUnknownReason reason) {
        if (reason == null) {
            return null;
        }
        String name = switch (reason) {
            case TIMEOUT -> "승인 응답 시간 초과";
            case SERVER_ERROR -> "결제사 서버 오류";
            case ALREADY_PROCESSED -> "이미 처리된 결제";
            case CONTRACT -> "결제사 응답 형식 불일치";
            case APPROVAL_MISMATCH -> "승인 정보 불일치";
            case STALE_IN_PROGRESS -> "승인 처리 정체";
            case MANUAL_REVIEW -> "운영자 확인 필요";
        };
        return CodeNameDescriptionMetadata.of(reason.name(), name, name);
    }
}
