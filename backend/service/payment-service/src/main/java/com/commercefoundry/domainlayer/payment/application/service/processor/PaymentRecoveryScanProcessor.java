package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentGatewayPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRecoveryQueryPort.Candidate;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.global.properties.PaymentRecoveryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 트랜잭션 없이 조합한다. 오래된 IN_PROGRESS는 GET 전 커밋하고 결과는 별도 트랜잭션에서 기록한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryScanProcessor {
    private final PaymentRecoveryQueryPort queries;
    private final PaymentRecoveryProcessor processor;
    private final PaymentGatewayPort gateway;
    private final PaymentRecoveryProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final EnumMap<PaymentStatus, Candidate> cursors = new EnumMap<>(PaymentStatus.class);

    public void recover() {
        if (!properties.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant tickNow = clock.instant();
            scan(PaymentStatus.UNKNOWN, properties.unknownInquiryAfter(), tickNow);
            scan(PaymentStatus.IN_PROGRESS, properties.staleInProgressAfter(), tickNow);
            scan(PaymentStatus.READY, properties.returnedReadyAfter(), tickNow);
        } finally {
            running.set(false);
        }
    }

    private void scan(PaymentStatus lane, Duration delay, Instant tickNow) {
        try {
            for (int page = 0; page < properties.maxPagesPerStatusPerTick(); page++) {
                var candidates = queries.findCandidates(lane, tickNow.minus(delay), cursors.get(lane), properties.pageSize());
                for (Candidate candidate : candidates) {
                    cursors.put(lane, candidate);
                    try {
                        processor.prepare(candidate.id(), lane, tickNow).ifPresent(snapshot -> {
                            var result = gateway.inquireByPaymentKey(snapshot.paymentKey(), snapshot.pgOrderId());
                            processor.finish(snapshot, result, tickNow);
                        });
                    } catch (RuntimeException failure) {
                        // 실패한 트랜잭션 밖이다. PG 원문 메시지나 결제 객체를 로그에 싣지 않는다.
                        log.warn("payment recovery candidate failed. paymentId={}, type={}", candidate.id(), failure.getClass().getSimpleName());
                    }
                }
                if (candidates.size() < properties.pageSize()) {
                    cursors.remove(lane); // 다음 tick에서만 처음부터 시작한다. 같은 tick 안에서는 되돌아가지 않는다.
                    break;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("payment recovery lane failed. lane={}, type={}", lane, failure.getClass().getSimpleName());
        }
    }
}
