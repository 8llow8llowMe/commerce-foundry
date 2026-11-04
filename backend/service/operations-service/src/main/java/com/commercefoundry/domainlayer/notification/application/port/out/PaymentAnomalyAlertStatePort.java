package com.commercefoundry.domainlayer.notification.application.port.out;

import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import java.util.Optional;

/** 호출자 트랜잭션 안에서 전체 잠금 조회 후 초기 INSERT 또는 기존 관리 상태 갱신만 수행한다. */
public interface PaymentAnomalyAlertStatePort {
    Optional<PaymentAnomalyPolicy.State> findLocked();
    void insertInitial(PaymentAnomalyPolicy.State state);
    void updateLocked(PaymentAnomalyPolicy.State state);
}
