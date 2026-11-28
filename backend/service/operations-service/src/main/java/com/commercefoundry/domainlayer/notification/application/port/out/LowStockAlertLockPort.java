package com.commercefoundry.domainlayer.notification.application.port.out;

/** 품절 임박 정기 점검(#229)의 인스턴스 간 단일 실행. 락을 얻지 못하면 {@code action} 을 실행하지 않고 돌아온다. */
public interface LowStockAlertLockPort {
    void runLocked(Runnable action);
}
