package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentLedgerQueryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancelLedger;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentLedgerEntry;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 대사(#219, {@code payment-guide.md} §10)가 읽는 원장 — 결제 시도와 그 취소 원장 합계({@link PaymentCancelLedger}). 다른 컨텍스트(settlement)의 Facade 만 부른다
 * ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙). 읽기 전용이고 결제 상태를 바꾸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PaymentLedgerProcessor {

    private final PaymentLedgerQueryPort ledgerQueryPort;

    /**
     * 결제키가 {@code paymentKeys} 에 있거나 PG 승인 시각이 {@code [approvedFrom, approvedTo)} 이고 통화가 {@code currency} 인 결제 시도의 합집합을 취소 원장 합계와 묶는다.
     * 결제키 조회는 통화로 거르지 않는다 — 다른 통화 결제를 걸러 낼지는 호출자가 정한다. 질의는 결제키 · 기간 · 취소 IN 절 각 한 번(목록이 길면 조각마다)이다.
     *
     * <p>호출자의 트랜잭션에 참여한다 — 대사 판정과 불일치 기록이 같은 스냅숏을 보게.
     *
     * @throws IllegalArgumentException 취소 원장 행의 통화가 결제 통화와 다를 때 — 데이터 오류라 조용히 넘기지 않는다({@link PaymentCancelLedger#of})
     */
    @Transactional(readOnly = true)
    public List<PaymentLedgerEntry> findForSettlement(Collection<String> paymentKeys, Instant approvedFrom, Instant approvedTo, CurrencyCode currency) {
        Map<Long, Payment> payments = new LinkedHashMap<>();
        if (!paymentKeys.isEmpty()) {
            ledgerQueryPort.findByPaymentKeys(paymentKeys).forEach(payment -> payments.put(payment.id(), payment));
        }
        ledgerQueryPort.findApprovedBetween(approvedFrom, approvedTo, currency).forEach(payment -> payments.putIfAbsent(payment.id(), payment));
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PaymentCancel>> cancelsByPayment = ledgerQueryPort.findCancelsByPaymentIds(payments.keySet()).stream()
            .collect(Collectors.groupingBy(PaymentCancel::paymentId));
        return payments.values().stream()
            .map(payment -> new PaymentLedgerEntry(payment,
                PaymentCancelLedger.of(cancelsByPayment.getOrDefault(payment.id(), List.of()), payment.amount().currency())))
            .toList();
    }
}
