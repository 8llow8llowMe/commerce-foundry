package com.commercefoundry.domainlayer.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentLedgerProcessor;
import com.commercefoundry.domainlayer.settlement.adapter.in.internal.presenter.SettlementReconcileInternalPresenter;
import com.commercefoundry.domainlayer.settlement.application.command.SettlementReconcileCommand;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.info.SettlementReconcileInfo;
import com.commercefoundry.domainlayer.settlement.application.model.SettlementReconcileTarget;
import com.commercefoundry.domainlayer.settlement.application.service.processor.SettlementCollectProcessor;
import com.commercefoundry.domainlayer.settlement.application.service.processor.SettlementReconcileProcessor;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementCollectionSummary;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDayStatus;
import com.commercefoundry.global.properties.PaymentSettlementProperties;
import com.commercefoundry.messaging.support.NoOpTransactionManager;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class SettlementReconcileInternalFacadeTest {

    /** 2026-11-25 09:00 KST. */
    static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");

    final SettlementCollectProcessor collect = mock(SettlementCollectProcessor.class);
    final SettlementReconcileProcessor reconcile = mock(SettlementReconcileProcessor.class);
    final PaymentLedgerProcessor ledger = mock(PaymentLedgerProcessor.class);
    final SettlementReconcileInternalFacade facade = new SettlementReconcileInternalFacade(collect, reconcile, ledger,
        new SettlementReconcileInternalPresenter(), new NoOpTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("수집 → 저장 → 대사 순서로 잇고, 대사 트랜잭션 안에서 payment 원장을 KST 하루 범위 · KRW 로 읽는다")
    void orchestratesInOrder() {
        List<PgSettlementItem> items = List.of();
        SettlementDay day = SettlementDay.collected(1L, SOLD_DATE, new SettlementCollectionSummary(0, 0, 0, 0, CurrencyCode.KRW), NOW);
        SettlementReconcileTarget target = new SettlementReconcileTarget(day, Set.of("tgen_facade_0001"), Instant.parse("2026-11-19T15:00:00Z"),
            Instant.parse("2026-11-20T15:00:00Z"));
        when(collect.fetch(SOLD_DATE)).thenReturn(items);
        when(reconcile.prepare(SOLD_DATE)).thenReturn(target);
        when(ledger.findForSettlement(target.paymentKeys(), target.approvedFrom(), target.approvedTo(), CurrencyCode.KRW)).thenReturn(List.of());
        when(reconcile.reconcile(eq(target), anyList(), eq(LocalDate.parse("2026-11-25")), any()))
            .thenReturn(new SettlementReconcileInfo(SOLD_DATE, 0, -1_000, CurrencyCode.KRW, 1, 1, 0, 0, SettlementDayStatus.RECONCILED));

        var response = facade.reconcile(new SettlementReconcileCommand(SOLD_DATE));

        InOrder order = inOrder(collect, reconcile, ledger);
        order.verify(collect).requireReconcilable(SOLD_DATE, NOW);
        order.verify(collect).fetch(SOLD_DATE);
        order.verify(collect).recordCollection(eq(SOLD_DATE), eq(items), any());
        order.verify(reconcile).prepare(SOLD_DATE);
        order.verify(ledger).findForSettlement(target.paymentKeys(), target.approvedFrom(), target.approvedTo(), CurrencyCode.KRW);
        order.verify(reconcile).reconcile(eq(target), anyList(), eq(LocalDate.parse("2026-11-25")), any());
        assertThat(response.pgAmountTotal().amount()).isEqualTo(-1_000);
        assertThat(response.pgAmountTotal().currency()).isEqualTo("KRW");
        assertThat(response.status().code()).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("PG 수집이 실패하면 매출일을 FAILED 로 남기고 503 SETTLEMENT_003 — 저장 · 대사는 하지 않는다")
    void collectionFailureRecordsFailedDay() {
        when(collect.fetch(SOLD_DATE)).thenThrow(new SettlementCollectionException(SettlementCollectionException.Failure.UNAVAILABLE));

        assertThatThrownBy(() -> facade.reconcile(new SettlementReconcileCommand(SOLD_DATE))).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.PG_COLLECTION_FAILED));
        verify(collect).recordFailure(SOLD_DATE);
        verify(collect, never()).recordCollection(any(), any(), any());
        verifyNoInteractions(reconcile, ledger);
    }

    @Test
    @DisplayName("실패 기록마저 실패해도(다른 실행이 잡고 있음) 수집 실패 503 을 가리지 않는다")
    void failureRecordErrorDoesNotMaskCollectionFailure() {
        when(collect.fetch(SOLD_DATE)).thenThrow(new SettlementCollectionException(SettlementCollectionException.Failure.RATE_LIMITED));
        when(collect.recordFailure(SOLD_DATE)).thenThrow(new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT));

        assertThatThrownBy(() -> facade.reconcile(new SettlementReconcileCommand(SOLD_DATE))).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.PG_COLLECTION_FAILED));
    }

    @Test
    @DisplayName("플래그 OFF — PG · DB 전에 409 SETTLEMENT_001")
    void disabledRejectsBeforePg() {
        SettlementCollectProcessor real = collectProcessor(false);
        SettlementReconcileInternalFacade disabled = new SettlementReconcileInternalFacade(real, reconcile, ledger, new SettlementReconcileInternalPresenter(),
            new NoOpTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> disabled.reconcile(new SettlementReconcileCommand(SOLD_DATE))).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.SETTLEMENT_DISABLED));
        verifyNoInteractions(reconcile, ledger);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-26", "2026-08-26"})
    @DisplayName("오늘(KST) 이후 · 90일보다 앞선 매출일은 400 SETTLEMENT_102")
    void outOfRangeDateRejected(String soldDate) {
        SettlementCollectProcessor real = collectProcessor(true);

        assertThatThrownBy(() -> real.requireReconcilable(LocalDate.parse(soldDate), NOW)).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.SOLD_DATE_OUT_OF_RANGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-25", "2026-08-27"})
    @DisplayName("오늘(KST) · 정확히 90일 전은 받는다")
    void boundaryDatesAccepted(String soldDate) {
        collectProcessor(true).requireReconcilable(LocalDate.parse(soldDate), NOW);
    }

    private static SettlementCollectProcessor collectProcessor(boolean enabled) {
        return new SettlementCollectProcessor(soldDate -> {
            throw new AssertionError("PG 를 부르면 안 된다");
        }, null, null, new SnowflakeIdGenerator(1, 1), new PaymentSettlementProperties(enabled));
    }
}
