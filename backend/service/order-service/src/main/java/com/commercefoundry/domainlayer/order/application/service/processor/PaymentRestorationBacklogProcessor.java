package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import com.commercefoundry.global.properties.OrderPaymentRestorationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 반영 재고 복원 적체 경고 (#582, {@code order-flow-guide.md} §3-6) — 스윕 틱마다 가장 오래된 복원 대기 작업의 나이와 HELD 수를 읽어
 * 임계를 넘으면 로그를 남긴다. 운영 알림 규칙은 이 문구를 잡는다. 같은 심각도는 1분에 한 번만 남긴다(인스턴스 메모리 기준).
 *
 * <ul>
 *   <li>나이 ≥ {@code backlog-error-age} — ERROR. 스윕이 멈췄거나 product 장애가 길다.</li>
 *   <li>나이 ≥ {@code backlog-warn-age} — WARN.</li>
 *   <li>HELD &gt; 0 — WARN. 사람이 확인할 작업이 있다(해제는 운영 SQL, §3-6).</li>
 * </ul>
 * 주문 · 결제 아이디는 남기지 않는다 — 건수와 나이만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRestorationBacklogProcessor {

    static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    private final OrderStockRestorationRepositoryPort workRepository;
    private final OrderPaymentRestorationProperties properties;
    private final Clock clock;
    private volatile Instant lastAgeLogAt;
    private volatile Instant lastHeldLogAt;

    @Transactional(readOnly = true, timeout = 5)
    public void warnBacklog() {
        Instant now = clock.instant();
        Optional<Instant> oldest = workRepository.findOldestCreatedAt(Status.RESTORE_PENDING);
        if (oldest.isPresent()) {
            Duration age = Duration.between(oldest.get(), now);
            if (age.compareTo(properties.backlogWarnAge()) >= 0 && due(lastAgeLogAt, now)) {
                lastAgeLogAt = now;
                long pending = workRepository.countByStatus(Status.RESTORE_PENDING);
                if (age.compareTo(properties.backlogErrorAge()) >= 0) {
                    log.error("payment restoration backlog too old. pending={}, oldestAgeSeconds={}", pending, age.toSeconds());
                } else {
                    log.warn("payment restoration backlog aging. pending={}, oldestAgeSeconds={}", pending, age.toSeconds());
                }
            }
        }
        long held = workRepository.countByStatus(Status.HELD);
        if (held > 0 && due(lastHeldLogAt, now)) {
            lastHeldLogAt = now;
            log.warn("payment restoration held for manual review. held={}", held);
        }
    }

    private static boolean due(Instant last, Instant now) {
        return last == null || !now.isBefore(last.plus(LOG_INTERVAL));
    }
}
