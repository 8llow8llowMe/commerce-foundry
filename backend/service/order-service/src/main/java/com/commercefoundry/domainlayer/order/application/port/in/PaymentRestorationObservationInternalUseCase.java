package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.PaymentRestorationObservationInternalResponse;

/** 결제 반영 재고 복원 작업 관측 (#808) — operations-service 의 이상 알림 폴링이 읽는다. */
public interface PaymentRestorationObservationInternalUseCase {

    PaymentRestorationObservationInternalResponse observe();
}
