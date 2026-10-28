package com.commercefoundry.domainlayer.job.application.port.out;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 정기 작업 등록을 인스턴스 하나만 실행한다.
 */
public interface JobPlannerLockPort {

    /**
     * 락을 잡으면 {@code action} 을 부르고 그 결과를 돌려준다. 락을 못 잡으면 {@code action} 을 부르지 않고 비어 있다.
     * {@code action} 이 던진 예외는 그대로 나간다.
     */
    <T> Optional<T> runLocked(Supplier<T> action);
}
