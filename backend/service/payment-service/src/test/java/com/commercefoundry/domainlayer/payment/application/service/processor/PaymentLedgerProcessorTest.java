package com.commercefoundry.domainlayer.payment.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentLedgerQueryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentApproval;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentLedgerEntry;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 정산 대사용 원장 조회 (#219) — 결제키 · 승인 범위 합집합, 취소 원장 합계, 질의 횟수. */
class PaymentLedgerProcessorTest {

    static final Instant FROM = Instant.parse("2026-11-19T15:00:00Z");
    static final Instant TO = Instant.parse("2026-11-20T15:00:00Z");
    static final Instant APPROVED_AT = Instant.parse("2026-11-20T01:00:00Z");

    @Test
    @DisplayName("결제키 조회와 승인 범위 조회의 합집합을 결제마다 한 번 · 취소 원장 합계(PG 취소 · 환불)와 묶는다 — 취소 조회는 한 번")
    void unionsAndAttachesCancelLedger() {
        Payment shared = done(9_000_000_001L, "tgen_ledger_proc_0001");
        Payment keyOnly = done(9_000_000_002L, "tgen_ledger_proc_0002");
        Payment rangeOnly = done(9_000_000_003L, "tgen_ledger_proc_0003");
        FakePort port = new FakePort(List.of(shared, keyOnly), List.of(shared, rangeOnly), List.of(
            new PaymentCancel(11L, shared.id(), krw(9_000), PaymentCancel.Status.SUCCEEDED),
            new PaymentCancel(12L, shared.id(), krw(1_000), PaymentCancel.Status.OFF_PG_REFUNDED),
            new PaymentCancel(13L, shared.id(), krw(500), PaymentCancel.Status.PENDING)));

        List<PaymentLedgerEntry> entries = new PaymentLedgerProcessor(port)
            .findForSettlement(List.of(shared.paymentKey(), keyOnly.paymentKey()), FROM, TO, CurrencyCode.KRW);

        assertThat(entries).extracting(entry -> entry.payment().id()).containsExactly(shared.id(), keyOnly.id(), rangeOnly.id());
        PaymentLedgerEntry first = entries.getFirst();
        assertThat(first.cancelLedger().pgCanceled()).isEqualTo(krw(9_000));
        assertThat(first.cancelLedger().refundedTotal()).isEqualTo(krw(10_000));
        assertThat(entries.get(1).cancelLedger().pgCanceled()).isEqualTo(krw(0));
        assertThat(port.cancelQueries).isEqualTo(1);
        assertThat(port.requestedCancelIds).containsExactlyInAnyOrder(shared.id(), keyOnly.id(), rangeOnly.id());
    }

    @Test
    @DisplayName("결제키가 없으면 결제키 조회를 건너뛰고, 대상이 없으면 취소를 조회하지 않는다")
    void skipsEmptyQueries() {
        FakePort port = new FakePort(List.of(), List.of(), List.of());

        assertThat(new PaymentLedgerProcessor(port).findForSettlement(List.of(), FROM, TO, CurrencyCode.KRW)).isEmpty();
        assertThat(port.keyQueries).isZero();
        assertThat(port.cancelQueries).isZero();
    }

    @Test
    @DisplayName("취소 원장 통화가 결제 통화와 다르면 데이터 오류라 실패한다")
    void cancelCurrencyMismatchFails() {
        Payment payment = done(9_000_000_001L, "tgen_ledger_proc_0001");
        FakePort port = new FakePort(List.of(payment), List.of(), List.of(
            new PaymentCancel(11L, payment.id(), Money.of(100, CurrencyCode.USD), PaymentCancel.Status.SUCCEEDED)));

        assertThatThrownBy(() -> new PaymentLedgerProcessor(port).findForSettlement(List.of(payment.paymentKey()), FROM, TO, CurrencyCode.KRW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Money krw(long amount) {
        return Money.of(amount, CurrencyCode.KRW);
    }

    static Payment done(long id, String paymentKey) {
        return Payment.ready(id, id + 1, 7_000_000_001L, krw(129_000), "생활한복 저고리")
            .startApproval(paymentKey, "3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11", APPROVED_AT.minusSeconds(3))
            .approve(new PaymentApproval(paymentKey, PaymentMethod.CARD, null, APPROVED_AT, "DONE", krw(129_000)));
    }

    static final class FakePort implements PaymentLedgerQueryPort {

        final List<Payment> byKeys;
        final List<Payment> byRange;
        final List<PaymentCancel> cancels;
        final List<Long> requestedCancelIds = new ArrayList<>();
        int keyQueries;
        int cancelQueries;

        FakePort(List<Payment> byKeys, List<Payment> byRange, List<PaymentCancel> cancels) {
            this.byKeys = byKeys;
            this.byRange = byRange;
            this.cancels = cancels;
        }

        @Override
        public List<Payment> findByPaymentKeys(Collection<String> paymentKeys) {
            keyQueries++;
            return byKeys.stream().filter(payment -> paymentKeys.contains(payment.paymentKey())).toList();
        }

        @Override
        public List<Payment> findApprovedBetween(Instant from, Instant to, CurrencyCode currency) {
            return byRange;
        }

        @Override
        public List<PaymentCancel> findCancelsByPaymentIds(Collection<Long> paymentIds) {
            cancelQueries++;
            requestedCancelIds.addAll(paymentIds);
            return cancels.stream().filter(cancel -> paymentIds.contains(cancel.paymentId())).toList();
        }
    }
}
