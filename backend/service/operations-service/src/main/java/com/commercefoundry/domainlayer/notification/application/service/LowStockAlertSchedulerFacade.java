package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.application.port.in.LowStockAlertSchedulerUseCase;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockObservationProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertPushProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.global.properties.LowStockAlertProperties;
import java.util.List;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 품절 임박 정기 점검(#229) 한 번: 락 → 수신자 → product 커서 순회 → 저장(15초 트랜잭션) → 커밋 뒤 SSE 전달.
 *
 * <p>원격 호출(auth · product)은 모두 DB 트랜잭션 밖이다. 어느 단계든 실패하면 고정 사유만 로그로 남기고 끝낸다 — 상태 · 알림을 쓰지 않으며
 * 다음 주기에 처음부터 다시 읽는다. 로그에는 SKU 아이디 · 상품명 · 원격 본문 · 예외 원문을 넣지 않는다.
 */
@Slf4j
@Service
public class LowStockAlertSchedulerFacade implements LowStockAlertSchedulerUseCase {
    private final LowStockObservationProcessor observations;
    private final LowStockAlertStateProcessor states;
    private final PaymentAnomalyAlertPushProcessor push;
    private final LowStockAlertProperties properties;
    private final LongSupplier ticker;

    public LowStockAlertSchedulerFacade(LowStockObservationProcessor observations, LowStockAlertStateProcessor states, PaymentAnomalyAlertPushProcessor push,
                                        LowStockAlertProperties properties, @Qualifier("lowStockAlertTicker") LongSupplier ticker) {
        this.observations = observations;
        this.states = states;
        this.push = push;
        this.properties = properties;
        this.ticker = ticker;
    }

    /**
     * 원격 I/O 가 섞여 Facade 에는 트랜잭션을 걸지 않는다 — 호출자 트랜잭션이 있어도 중단(NOT_SUPPORTED)해 HTTP 동안 커넥션을 쥐지 않고,
     * 저장 Processor 의 트랜잭션이 독립 커밋된 뒤에만 전달한다.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void observe() {
        if (!properties.enabled()) {
            return;
        }
        try {
            observations.runLocked(this::collect);
        } catch (RuntimeException ignored) {
            log.warn("low_stock_alert_failed reason=lock");
        }
    }

    private void collect() {
        // 점검 시작(락 획득) 시각. 저장 트랜잭션이 잠금 뒤 경과를 같은 단조 시계로 재서 lock-at-most 를 넘었으면 쓰지 않는다.
        long started = ticker.getAsLong();
        List<Long> recipients;
        try {
            recipients = observations.recipients();
        } catch (RuntimeException ignored) {
            // 실제 auth 어댑터는 관리자 0명 · 1,000명 초과도 RECIPIENTS_UNAVAILABLE 로 던진다 — 운영에서는 이 사유로 남는다.
            log.warn("low_stock_alert_failed reason=recipient");
            return;
        }
        // 아래 두 분기는 방어 코드다. 포트가 예외 대신 빈 목록 · 상한 초과 목록을 돌려주는 구현일 때만 탄다.
        if (recipients == null || recipients.isEmpty()) {
            // 받을 사람이 없으면 추적 행도 만들지 않는다 — 수신자가 생긴 뒤 첫 점검이 새 알림을 낸다.
            log.warn("low_stock_alert_skipped reason=no_recipient");
            return;
        }
        if (recipients.size() > LowStockAlertPolicy.MAX_RECIPIENTS) {
            log.warn("low_stock_alert_failed reason=recipient_limit");
            return;
        }
        LowStockAlertPolicy.Scan scan;
        try {
            scan = observations.scan(properties.maxPages());
        } catch (RuntimeException ignored) {
            log.warn("low_stock_alert_failed reason=source");
            return;
        }
        List<Notification> saved;
        try {
            saved = states.accept(scan, recipients, properties.settings(), started);
        } catch (RuntimeException ignored) {
            log.warn("low_stock_alert_failed reason=storage");
            return;
        }
        if (saved.isEmpty()) {
            return;
        }
        try {
            // 요약 1건 × 수신자(최대 1,000건)라 전달 배치 상한(5,000) 안이다. 최선 전달이며 실패해도 저장을 되돌리지 않는다 — 목록 조회로 따라잡는다.
            push.enqueue(saved);
        } catch (RuntimeException ignored) {
            log.warn("low_stock_alert_push_failed reason=postcommit");
        }
    }
}
