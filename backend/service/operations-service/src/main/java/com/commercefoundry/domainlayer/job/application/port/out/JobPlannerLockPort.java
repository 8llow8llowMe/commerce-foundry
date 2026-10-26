package com.commercefoundry.domainlayer.job.application.port.out;

/**
 * 정기 작업 등록을 인스턴스 하나만 실행한다. 락을 못 잡으면 {@code action} 을 부르지 않는다.
 */
public interface JobPlannerLockPort {

    void runLocked(Runnable action);
}
