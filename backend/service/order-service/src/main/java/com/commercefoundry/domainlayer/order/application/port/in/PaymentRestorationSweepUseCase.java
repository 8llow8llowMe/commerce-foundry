package com.commercefoundry.domainlayer.order.application.port.in;

import java.time.Duration;

/** 결제 반영 복원 작업을 한 배치 실행한다. */
public interface PaymentRestorationSweepUseCase {

    void sweep(int batchSize, Duration initialBackoff, Duration maxBackoff);
}
