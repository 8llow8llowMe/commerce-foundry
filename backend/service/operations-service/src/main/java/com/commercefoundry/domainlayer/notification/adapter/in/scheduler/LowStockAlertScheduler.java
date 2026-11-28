package com.commercefoundry.domainlayer.notification.adapter.in.scheduler;

import com.commercefoundry.domainlayer.notification.application.port.in.LowStockAlertSchedulerUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 품절 임박 정기 점검(#229). {@code operations.notifications.low-stock-alert.enabled=true} 일 때만 전용 1스레드 스케줄러에 등록된다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("operations.notifications.low-stock-alert.enabled")
public class LowStockAlertScheduler {
    private final LowStockAlertSchedulerUseCase useCase;

    @Scheduled(scheduler = "lowStockAlertTaskScheduler",
        initialDelayString = "${operations.notifications.low-stock-alert.initial-delay:2m}",
        fixedDelayString = "${operations.notifications.low-stock-alert.fixed-delay:10m}")
    public void observe() {
        try {
            useCase.observe();
        } catch (RuntimeException ignored) {
            // 예외 원문(원격 본문 · SKU 정보가 섞일 수 있음)을 남기지 않는다. 다음 주기에 처음부터 다시 읽는다.
            log.warn("low_stock_alert_tick_failed");
        }
    }
}
