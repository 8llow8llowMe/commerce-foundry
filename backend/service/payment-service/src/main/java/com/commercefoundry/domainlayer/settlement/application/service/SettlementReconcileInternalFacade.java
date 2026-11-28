package com.commercefoundry.domainlayer.settlement.application.service;

import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentLedgerProcessor;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentLedgerEntry;
import com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.response.SettlementReconcileResponse;
import com.commercefoundry.domainlayer.settlement.adapter.in.internal.presenter.SettlementReconcileInternalPresenter;
import com.commercefoundry.domainlayer.settlement.application.command.SettlementReconcileCommand;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.info.SettlementReconcileInfo;
import com.commercefoundry.domainlayer.settlement.application.model.SettlementReconcileTarget;
import com.commercefoundry.domainlayer.settlement.application.port.in.SettlementReconcileInternalUseCase;
import com.commercefoundry.domainlayer.settlement.application.service.processor.SettlementCollectProcessor;
import com.commercefoundry.domainlayer.settlement.application.service.processor.SettlementReconcileProcessor;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementCalendar;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementReconciliation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PG 매출일 하루의 정산 수집 · 대사 (#219, {@code payment-guide.md} §10). 순서: ① 플래그 · 날짜 확인 → ② PG 수집(트랜잭션 밖, 모든 페이지) →
 * ③ 짧은 트랜잭션으로 거래 upsert · 매출일 COLLECTED → ④ 대사 트랜잭션(매출일 잠금 · 원장 조회 · 판정 · 불일치 기록 · RECONCILED).
 * 수집이 실패하면 매출일을 FAILED 로 남기고 503 이다 — 호출자(operations 정기 작업)가 다시 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementReconcileInternalFacade implements SettlementReconcileInternalUseCase {

    /** 대사 트랜잭션 상한 — 하루 거래 수천 건의 판정 · 쓰기를 넉넉히 덮는다. */
    private static final int RECONCILE_TIMEOUT_SECONDS = 30;

    private final SettlementCollectProcessor collectProcessor;
    private final SettlementReconcileProcessor reconcileProcessor;
    private final PaymentLedgerProcessor paymentLedgerProcessor;
    private final SettlementReconcileInternalPresenter presenter;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /**
     * Facade 에 트랜잭션을 걸지 않는다(호출자 트랜잭션도 끊는다) — PG 정산 조회는 페이지마다 최대 60초 · 429 백오프까지 걸려 DB 커넥션을 쥔 채 기다리면 안 된다
     * ({@code architecture-guide.md} §3 Facade 예외). 저장 구간은 Processor 의 짧은 트랜잭션, 대사 구간은 payment 원장 조회와 정산 기록이 한 스냅숏이어야 해
     * 시간 상한이 있는 {@link TransactionTemplate} 으로 묶는다 — 다른 컨텍스트 Processor 는 Facade 가 부르고 받은 domain 만 정산 Processor(MANDATORY)에 넘긴다.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SettlementReconcileResponse reconcile(SettlementReconcileCommand command) {
        Instant startedAt = clock.instant();
        LocalDate soldDate = command.soldDate();
        collectProcessor.requireReconcilable(soldDate, startedAt);

        List<PgSettlementItem> items;
        try {
            items = collectProcessor.fetch(soldDate);
        } catch (SettlementCollectionException failure) {
            recordFailure(soldDate, failure);
            throw new SettlementException(SettlementErrorCode.PG_COLLECTION_FAILED);
        }
        collectProcessor.recordCollection(soldDate, items, clock.instant());

        LocalDate today = SettlementCalendar.today(startedAt);
        TransactionTemplate reconcileTransaction = new TransactionTemplate(transactionManager);
        reconcileTransaction.setTimeout(RECONCILE_TIMEOUT_SECONDS);
        SettlementReconcileInfo info = reconcileTransaction.execute(status -> {
            SettlementReconcileTarget target = reconcileProcessor.prepare(soldDate);
            List<PaymentLedgerEntry> ledger = paymentLedgerProcessor.findForSettlement(target.paymentKeys(), target.approvedFrom(), target.approvedTo(),
                SettlementReconciliation.CURRENCY);
            return reconcileProcessor.reconcile(target, ledger, today, clock.instant());
        });
        return presenter.toResponse(info);
    }

    /** 실패 기록이 실패해도(다른 실행이 매출일을 잡고 있음 등) 수집 실패 응답을 가리지 않는다 — 원인 타입만 남긴다. */
    private void recordFailure(LocalDate soldDate, SettlementCollectionException failure) {
        log.warn("settlement collection failed. soldDate={}, failure={}", soldDate, failure.getFailure());
        try {
            collectProcessor.recordFailure(soldDate);
        } catch (RuntimeException exception) {
            log.warn("settlement failure record skipped. soldDate={}, causeType={}", soldDate, exception.getClass().getSimpleName());
        }
    }
}
