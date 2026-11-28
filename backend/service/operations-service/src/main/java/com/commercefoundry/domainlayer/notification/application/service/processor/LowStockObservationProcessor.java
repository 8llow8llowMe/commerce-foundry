package com.commercefoundry.domainlayer.notification.application.service.processor;

import com.commercefoundry.domainlayer.notification.application.port.out.LowStockAlertLockPort;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockObservationPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 품절 임박 정기 점검(#229)의 원격 읽기 경계 — 락 · 수신자 · product 커서 순회. DB 트랜잭션을 열지 않는다.
 *
 * <p>커서 0 에서 시작해 앞 페이지의 다음 커서로 {@code maxPages} 까지 읽는다. 어느 페이지든 첫 실패(원천 장애 · 계약 위반)는 그대로 던져
 * 점검 전체를 버린다 — 앞서 읽은 페이지로 일부만 반영하거나 HTTP 를 다시 부르지 않는다. {@code maxPages} 에서 끊긴 점검은 잘린 스캔이며
 * 본 SKU 만 반영하고 부재 판정을 건너뛰는 것은 판정 정책 · 저장 쪽이 한다(WARN 로그도 저장 쪽이 수락 뒤 한 번 남긴다).
 */
@Component
@RequiredArgsConstructor
public class LowStockObservationProcessor {
    private final LowStockObservationPort observations;
    private final NotificationRecipientPort recipients;
    private final LowStockAlertLockPort lock;

    public void runLocked(Runnable action) {
        lock.runLocked(action);
    }

    /** auth 활성 대표 관리자(ADMIN · OWNER). 원천 장애 · 상한 초과는 예외다. */
    public List<Long> recipients() {
        return recipients.findAllActiveAdminIds();
    }

    public LowStockAlertPolicy.Scan scan(int maxPages) {
        if (maxPages < 1 || maxPages > LowStockAlertPolicy.MAX_PAGES_LIMIT) {
            throw new IllegalArgumentException("maxPages");
        }
        var pages = new ArrayList<LowStockAlertPolicy.Page>(maxPages);
        long afterSkuId = 0;
        for (int index = 0; index < maxPages; index++) {
            var page = Objects.requireNonNull(observations.observe(afterSkuId, LowStockAlertPolicy.PAGE_SIZE), "page");
            pages.add(page);
            if (!page.hasNext()) {
                break;
            }
            afterSkuId = page.nextAfterSkuId();
        }
        // 커서 연속성(건너뜀 · 되감기 · 끝난 뒤 이어짐)은 Scan.of 가 다시 확인한다.
        return LowStockAlertPolicy.Scan.of(pages);
    }
}
