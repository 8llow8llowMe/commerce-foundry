package com.commercefoundry.domainlayer.settlement.application.service.processor;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentLedgerEntry;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.info.SettlementReconcileInfo;
import com.commercefoundry.domainlayer.settlement.application.model.SettlementReconcileTarget;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementDayRepositoryPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementItemRepositoryPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementMismatchRepositoryPort;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementCalendar;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDayStatus;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDetection;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementJudgement;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementLedgerPayment;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatch;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementReconciliation;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementTransitionException;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 대사 (#219, {@code payment-guide.md} §10). 두 메서드 모두 Facade 의 대사 트랜잭션(MANDATORY) 안에서 돈다 — 매출일 행 잠금 · 원장 조회 · 판정 · 불일치 기록 ·
 * RECONCILED 가 한 스냅숏 · 한 커밋이다. 원장(payment 결제 시도 · 취소 원장)은 Facade 가 payment Processor 로 읽어 domain 으로 넘긴다
 * ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 *
 * <p>대상은 (a) 매출일에 수집된 KRW 거래의 결제키 전부 ∪ (b) 승인 시각이 매출일(KST) 안인 KRW 결제 전부다. 결제키마다 판정은 {@link SettlementReconciliation} 한 곳이다.
 * 같은 매출일을 몇 번 돌려도 결과가 같다 — 불일치는 (결제키, 종류)마다 한 행이고, 다시 찾으면 금액 · 발견 시각만 바꾸며, 더는 어긋나지 않으면 자동으로 닫는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementReconcileProcessor {

    private final SettlementDayRepositoryPort dayPort;
    private final SettlementItemRepositoryPort itemPort;
    private final SettlementMismatchRepositoryPort mismatchPort;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 매출일 행을 잠그고(NOWAIT) 원장 조회 범위를 낸다. 수집 직후의 COLLECTED 가 아니면(그 사이 다른 실행이 실패로 바꿨거나 이미 대사했다) 이 실행의 수집 결과가 아니라 409 다.
     *
     * @throws SettlementException 409 {@code SETTLEMENT_002}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementReconcileTarget prepare(LocalDate soldDate) {
        SettlementDay day = dayPort.lockBySoldDate(soldDate).orElseThrow(() -> new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT));
        if (day.status() != SettlementDayStatus.COLLECTED) {
            throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
        }
        Set<String> paymentKeys = itemPort.findPaymentKeysBySoldDate(soldDate, SettlementReconciliation.CURRENCY);
        return new SettlementReconcileTarget(day, paymentKeys, SettlementCalendar.startOf(soldDate), SettlementCalendar.endOf(soldDate));
    }

    /**
     * 대상 결제마다 판정하고 불일치를 기록 · 자동 해소한 뒤 매출일을 RECONCILED 로 둔다. 조회는 거래 · 불일치 IN 절 한 번씩이고, 쓰기는 바뀐 불일치 한 번 · 매출일 한 번이다.
     *
     * @param ledgerEntries {@code target} 의 결제키 · 승인 범위로 읽은 원장. KRW 가 아닌 결제는 여기서 뺀다(#738)
     * @param today 오늘(KST) — PG 누락 유예 기준. 실행을 시작한 시각으로 정한다
     * @param now 기록 시각
     * @throws SettlementException 409 {@code SETTLEMENT_002} — 다른 날짜의 대사가 같은 불일치를 먼저 바꿨거나 매출일 버전 충돌
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementReconcileInfo reconcile(SettlementReconcileTarget target, List<PaymentLedgerEntry> ledgerEntries, LocalDate today, Instant now) {
        LocalDate soldDate = target.day().soldDate();
        Map<String, SettlementLedgerPayment> ledger = new HashMap<>();
        Set<String> excluded = new HashSet<>();
        for (PaymentLedgerEntry entry : ledgerEntries) {
            Payment payment = entry.payment();
            if (payment.paymentKey() == null) {
                continue;
            }
            if (payment.amount().currency() != SettlementReconciliation.CURRENCY) {
                excluded.add(payment.paymentKey());
                continue;
            }
            ledger.put(payment.paymentKey(), toLedger(entry));
        }
        Set<String> paymentKeys = new TreeSet<>(target.paymentKeys());
        paymentKeys.addAll(ledger.keySet());
        paymentKeys.removeAll(excluded);

        Map<String, List<PgSettlementItem>> itemsByKey = itemPort.findByPaymentKeys(paymentKeys).stream()
            .filter(item -> item.currency() == SettlementReconciliation.CURRENCY)
            .collect(Collectors.groupingBy(PgSettlementItem::paymentKey));
        Map<MismatchKey, SettlementMismatch> mismatches = new HashMap<>();
        mismatchPort.findByPaymentKeys(paymentKeys).forEach(mismatch -> mismatches.put(new MismatchKey(mismatch.paymentKey(), mismatch.type()), mismatch));

        List<SettlementMismatch> changed = new ArrayList<>();
        int opened = 0;
        int cleared = 0;
        int graceDeferred = 0;
        for (String paymentKey : paymentKeys) {
            SettlementJudgement judgement = SettlementReconciliation.judge(paymentKey, itemsByKey.getOrDefault(paymentKey, List.of()), ledger.get(paymentKey),
                soldDate, today);
            if (judgement.graceDeferred()) {
                graceDeferred++;
            }
            for (SettlementDetection detection : judgement.detections()) {
                MismatchKey key = new MismatchKey(paymentKey, detection.type());
                SettlementMismatch current = mismatches.get(key);
                if (current == null || !current.isOpen()) {
                    opened++;
                }
                SettlementMismatch next = current == null
                    ? SettlementMismatch.open(idGenerator.generateId(), soldDate, detection, SettlementReconciliation.CURRENCY, now)
                    : current.redetect(detection, now);
                mismatches.put(key, next);
                changed.add(next);
            }
            for (SettlementMismatchType type : judgement.clearedTypes()) {
                MismatchKey key = new MismatchKey(paymentKey, type);
                SettlementMismatch current = mismatches.get(key);
                if (current != null && current.isOpen()) {
                    SettlementMismatch next = current.autoClear(now);
                    mismatches.put(key, next);
                    changed.add(next);
                    cleared++;
                }
            }
        }
        if (!changed.isEmpty()) {
            mismatchPort.saveAll(changed);
        }
        int open = (int) mismatches.values().stream().filter(SettlementMismatch::isOpen).count();
        SettlementDay reconciled;
        try {
            reconciled = dayPort.save(target.day().reconcile(open, now));
        } catch (SettlementTransitionException exception) {
            throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
        }
        if (!excluded.isEmpty()) {
            log.warn("settlement reconcile skipped non-KRW payments. soldDate={}, count={}", soldDate, excluded.size());
        }
        log.info("settlement reconciled. soldDate={}, payments={}, open={}, opened={}, autoCleared={}, graceDeferred={}",
            soldDate, paymentKeys.size(), open, opened, cleared, graceDeferred);
        return new SettlementReconcileInfo(soldDate, reconciled.itemCount(), reconciled.pgAmountTotal(), reconciled.currency(), open, opened, cleared,
            graceDeferred, reconciled.status());
    }

    /** payment 원장 → 정산 판정 값. 결제됨 계열 판정은 payment 의 상태 규칙({@code PaymentStatus.hasApproval})을 그대로 쓴다. */
    private static SettlementLedgerPayment toLedger(PaymentLedgerEntry entry) {
        Payment payment = entry.payment();
        return new SettlementLedgerPayment(payment.id(), payment.paymentKey(), payment.amount().amount(), entry.cancelLedger().pgCanceled().amount(),
            payment.amount().currency(), payment.status().hasApproval(), payment.approvedAt());
    }

    private record MismatchKey(String paymentKey, SettlementMismatchType type) {
    }
}
