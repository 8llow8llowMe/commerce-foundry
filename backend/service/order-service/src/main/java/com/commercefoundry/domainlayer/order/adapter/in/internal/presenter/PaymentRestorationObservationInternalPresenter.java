package com.commercefoundry.domainlayer.order.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.PaymentRestorationObservationInternalResponse;
import com.commercefoundry.domainlayer.order.application.info.PaymentRestorationObservationInfo;
import org.springframework.stereotype.Component;

@Component
public class PaymentRestorationObservationInternalPresenter {

    public PaymentRestorationObservationInternalResponse toResponse(PaymentRestorationObservationInfo info) {
        return new PaymentRestorationObservationInternalResponse(info.observedAt(), info.pendingCount(), info.oldestPendingCreatedAt(),
            info.heldCount(), info.oldestHeldCreatedAt());
    }
}
