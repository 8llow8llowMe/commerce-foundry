package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;

/**
 * product 품절 임박 관측 한 페이지(#229). 원천 장애 · 서킷 열림 · 응답 계약 위반은 모두 {@code NOTIFICATION_053} 예외다 —
 * 빈 페이지나 정상 결과로 바꾸지 않는다. 재시도하지 않는다.
 */
public interface LowStockObservationPort {
    LowStockAlertPolicy.Page observe(long afterSkuId, int size);
}
