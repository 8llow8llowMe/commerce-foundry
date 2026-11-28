package com.commercefoundry.domainlayer.settlement.application.service.processor;

import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementDayRepositoryPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementGatewayPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementItemRepositoryPort;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementCalendar;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementCollectionSummary;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementReconciliation;
import com.commercefoundry.global.properties.PaymentSettlementProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG 정산 수집 (#219, {@code payment-guide.md} §10). PG 조회는 트랜잭션 밖이고, 저장 구간만 이 Processor 의 짧은 트랜잭션이다 — Facade 가 순서를 잇는다.
 * 같은 매출일의 동시 실행은 매출일 행 잠금(NOWAIT)으로 둘째가 바로 409 다.
 */
@Component
@RequiredArgsConstructor
public class SettlementCollectProcessor {

    /** 거래 수천 건 upsert 를 넉넉히 덮는 상한. 넘으면 롤백되고 호출자가 다시 부른다. */
    private static final int COLLECTION_TIMEOUT_SECONDS = 30;
    private static final int FAILURE_TIMEOUT_SECONDS = 5;

    private final SettlementGatewayPort gatewayPort;
    private final SettlementItemRepositoryPort itemPort;
    private final SettlementDayRepositoryPort dayPort;
    private final SnowflakeIdGenerator idGenerator;
    private final PaymentSettlementProperties properties;

    /**
     * DB · PG 전에 본다 — 기능 플래그(기본 OFF), 매출일 범위(오늘 KST 이후 · 90일 전보다 앞선 날짜 거절).
     *
     * @throws SettlementException 409 {@code SETTLEMENT_001} 꺼짐 · 400 {@code SETTLEMENT_102} 범위 밖
     */
    public void requireReconcilable(LocalDate soldDate, Instant now) {
        if (!properties.enabled()) {
            throw new SettlementException(SettlementErrorCode.SETTLEMENT_DISABLED);
        }
        if (soldDate == null || !SettlementCalendar.reconcilable(soldDate, SettlementCalendar.today(now))) {
            throw new SettlementException(SettlementErrorCode.SOLD_DATE_OUT_OF_RANGE);
        }
    }

    /**
     * PG 에서 매출일의 정산 거래를 모두 읽는다. 트랜잭션을 걸지 않는다 — 한 페이지에 최대 60초(토스 권고 read timeout)라 DB 커넥션을 쥐고 기다리지 않게.
     *
     * @throws com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException 수집 실패
     */
    public List<PgSettlementItem> fetch(LocalDate soldDate) {
        return gatewayPort.fetchSettlements(soldDate);
    }

    /**
     * 수집 결과를 저장한다 — 매출일 행을 잠그고(없으면 만든다) COLLECTED 로 둔 뒤 거래를 거래키로 upsert 한다. 매출일 행을 먼저 써서 동시 첫 실행이 거래 upsert 전에 부딪치게 한다.
     *
     * @throws SettlementException 409 {@code SETTLEMENT_002} — 같은 매출일을 다른 실행이 잡고 있다
     */
    @Transactional(timeout = COLLECTION_TIMEOUT_SECONDS)
    public SettlementDay recordCollection(LocalDate soldDate, List<PgSettlementItem> items, Instant now) {
        SettlementCollectionSummary summary = SettlementCollectionSummary.of(items, SettlementReconciliation.CURRENCY);
        SettlementDay day = dayPort.lockBySoldDate(soldDate)
            .map(existing -> existing.recollect(summary, now))
            .orElseGet(() -> SettlementDay.collected(idGenerator.generateId(), soldDate, summary, now));
        SettlementDay saved = dayPort.save(day);
        itemPort.upsertAll(items, now);
        return saved;
    }

    /**
     * 수집 실패를 남긴다 — 매출일 행을 FAILED 로(없으면 요약 0 으로 만든다). 요약 칸은 마지막 성공 값 그대로다.
     *
     * @throws SettlementException 409 {@code SETTLEMENT_002} — 같은 매출일을 다른 실행이 잡고 있다
     */
    @Transactional(timeout = FAILURE_TIMEOUT_SECONDS)
    public SettlementDay recordFailure(LocalDate soldDate) {
        SettlementDay day = dayPort.lockBySoldDate(soldDate)
            .map(SettlementDay::fail)
            .orElseGet(() -> SettlementDay.failed(idGenerator.generateId(), soldDate, SettlementReconciliation.CURRENCY));
        return dayPort.save(day);
    }
}
