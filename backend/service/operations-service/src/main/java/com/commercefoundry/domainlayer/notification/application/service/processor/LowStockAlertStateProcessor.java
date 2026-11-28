package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.LowStockAlertStatePort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationBatchInsertPort;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationKind;
import com.commercefoundry.domainlayer.notification.domain.model.NotificationRequest;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 품절 임박 정기 점검(#229)의 저장 경계. 원천 HTTP · 수신자 조회 · 커밋 뒤 전달은 호출 쪽(Facade)의 책임이다.
 *
 * <p>워터마크 단일 행을 NOWAIT 로 잠근 뒤 판정하고, 상태 · 추적 행 · 수신자별 요약 알림 1건을 한 트랜잭션에서 저장한다.
 * 알림에는 건수만 싣는다 — 상품명 · SKU 아이디 · 수량 · 기준값을 넣지 않는다. 로그도 건수와 고정 사유만 남긴다.
 */
@Slf4j
@Component
public class LowStockAlertStateProcessor {
    static final String SOURCE_TYPE = "LOW_STOCK_ALERT";
    static final String LINK_PATH = "/admin/stocks";
    private final LowStockAlertStatePort states;
    private final NotificationBatchInsertPort notifications;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final LongSupplier ticker;

    public LowStockAlertStateProcessor(LowStockAlertStatePort states, NotificationBatchInsertPort notifications, SnowflakeIdGenerator ids, Clock clock,
                                       @Qualifier("lowStockAlertTicker") LongSupplier ticker) {
        this.states = states;
        this.notifications = notifications;
        this.ids = ids;
        this.clock = clock;
        this.ticker = ticker;
    }

    /**
     * 점검 결과를 수락하면 저장한 알림을 돌려준다(알림이 없거나 거부면 빈 목록).
     *
     * @param startedNanos 점검 시작(락 획득) 때의 {@code lowStockAlertTicker} 값. 잠금 뒤 경과가 {@code lockAtMost} 를 넘으면 아무것도 쓰지 않는다
     */
    @Transactional(timeout = 15)
    public List<Notification> accept(LowStockAlertPolicy.Scan scan, List<Long> recipients, LowStockAlertPolicy.Settings settings, long startedNanos) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(settings, "settings");
        var input = recipients(recipients);
        var previous = states.findLocked().orElse(null);
        var now = clock.instant();
        // nanoTime 의 시작값은 임의이며 overflow wrap 을 포함한 차이로 경과를 잰다.
        var elapsed = Duration.ofNanos(ticker.getAsLong() - startedNanos);
        var tracked = states.findAllTracked();
        var decision = LowStockAlertPolicy.decide(previous, tracked, scan, now, elapsed, settings);
        if (!decision.accepted()) {
            log.warn("low_stock_alert_rejected reason={}", decision.rejection());
            return List.of();
        }
        if (previous == null) {
            states.insertInitial(decision.state());
        } else {
            states.updateLocked(decision.state());
        }
        states.applySkuChanges(decision.changes());
        if (!decision.complete()) {
            // 잘린 점검은 본 SKU 만 반영하고 부재 판정을 건너뛴다.
            log.warn("low_stock_alert_scan_truncated reason=max_pages seen={}", scan.skuIds().size());
        }
        if (decision.notice() == LowStockAlertPolicy.Notice.NONE) {
            return List.of();
        }
        var title = title(decision.notice(), decision.count(), decision.complete());
        var pending = new ArrayList<Notification>(input.size());
        for (long recipient : input) {
            long id = ids.generateId();
            pending.add(Notification.create(id, new NotificationRequest(NotificationKind.LOW_STOCK, decision.notice().severity(), title, LINK_PATH,
                recipient, SOURCE_TYPE, id)));
        }
        return List.copyOf(notifications.insertAll(pending));
    }

    private static List<Long> recipients(List<Long> recipients) {
        if (recipients == null || recipients.isEmpty() || recipients.size() > LowStockAlertPolicy.MAX_RECIPIENTS) {
            throw new IllegalArgumentException("Invalid low stock alert recipients");
        }
        var distinct = new HashSet<Long>();
        for (var recipient : recipients) {
            if (recipient == null || recipient <= 0 || !distinct.add(recipient)) {
                throw new IllegalArgumentException("Invalid low stock alert recipients");
            }
        }
        return List.copyOf(recipients);
    }

    static String title(LowStockAlertPolicy.Notice notice, int count, boolean complete) {
        var amount = String.format(Locale.ROOT, "%,d", count);
        return switch (notice) {
            case NEW_LOW_STOCK -> "품절 임박 상품 옵션 " + amount + "개가 새로 생겼습니다. 재고를 확인해 주세요.";
            case STILL_LOW_STOCK -> "품절 임박 상품 옵션 " + amount + "개" + (complete ? "가" : " 이상이") + " 아직 남아 있습니다. 재고를 확인해 주세요.";
            case NONE -> throw new IllegalArgumentException("notice");
        };
    }
}
