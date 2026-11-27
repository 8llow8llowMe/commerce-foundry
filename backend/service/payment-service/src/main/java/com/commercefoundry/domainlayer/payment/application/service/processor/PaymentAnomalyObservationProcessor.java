package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentAnomalyObservationProcessor {
    private final PaymentAnomalyObservationPort repository;
    private final Clock clock;
    @Value("${payment.auto-cancel.enabled:false}")
    private boolean autoCancelEnabled;

    @Transactional(readOnly = true, timeout = 2)
    public PaymentAnomalyObservation observeAnomalies() {
        // PostgreSQL 정밀도로 한 번 고정한 시각을 두 쿼리와 응답에 함께 쓴다.
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        PaymentAnomalyObservation observation = repository.observeAnomalies(observedAt);
        if (!autoCancelEnabled) {
            // 게이트가 꺼진 DB 에는 payment_auto_cancel 이 없을 수 있다(SchemaGuard 는 게이트 ON 에서만 돈다).
            // 0건으로 보내면 롤백 순간 operations 가 사건을 후보 소멸로 닫으므로 질의하지 않고 미관측(null)으로 둔다.
            return observation.withAutoCancelManualReview(null);
        }
        // 앞 여섯 버킷과 같은 트랜잭션의 두 번째 문장이다. 실패하면 그대로 올려 관측 전체를 503 으로 만든다.
        return observation.withAutoCancelManualReview(repository.observeAutoCancelManualReview(observedAt));
    }
}
