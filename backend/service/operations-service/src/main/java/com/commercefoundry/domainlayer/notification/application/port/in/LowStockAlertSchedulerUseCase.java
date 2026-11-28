package com.commercefoundry.domainlayer.notification.application.port.in;

/** 품절 임박 정기 점검 한 번(#229). 실패는 안에서 고정 사유 로그로 끝내고 호출자에게 던지지 않는다. */
public interface LowStockAlertSchedulerUseCase {
    void observe();
}
