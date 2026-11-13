package com.commercefoundry.domainlayer.payment.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.domainlayer.payment.application.info.PaymentAdminInfo;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminStatusFilter;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAdminQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminExternalRefundQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 어드민 결제 목록의 외부 확인 환불 합계 묶기 (#693) — 페이지당 한 번, 채널별 합, 없으면 null. */
class PaymentAdminQueryProcessorTest {
    private static final Instant TIME = Instant.parse("2026-11-01T00:00:00Z");
    private static final PaymentAdminSearchCommand COMMAND = new PaymentAdminSearchCommand(PaymentAdminStatusFilter.EXTERNAL_REFUNDED, null, null, 0, 20);

    private PaymentAdminQueryPort port;
    private PaymentAdminQueryProcessor processor;

    @BeforeEach
    void setUp() {
        port = mock(PaymentAdminQueryPort.class);
        processor = new PaymentAdminQueryProcessor(port);
    }

    @Test
    @DisplayName("페이지의 결제 아이디로 한 번만 집계하고 채널별로 더한다. 없는 채널은 결제 통화의 0, 원장 없는 결제는 null 이다")
    void groupsPageTotalsByChannelWithOneAggregateCall() {
        given(port.search(any())).willReturn(page(row(3, CurrencyCode.USD), row(2, CurrencyCode.KRW), row(1, CurrencyCode.KRW)));
        given(port.findExternalRefundTotals(any())).willReturn(List.of(
            total(1, PaymentCancel.Status.EXTERNAL_PG_REFUNDED, 500, CurrencyCode.KRW), total(1, PaymentCancel.Status.OFF_PG_REFUNDED, 300, CurrencyCode.KRW),
            total(3, PaymentCancel.Status.OFF_PG_REFUNDED, 1250, CurrencyCode.USD)));

        List<PaymentAdminInfo> infos = processor.search(COMMAND).getContent();

        verify(port, times(1)).findExternalRefundTotals(Set.of(1L, 2L, 3L));
        assertThat(infos).extracting(PaymentAdminInfo::externalRefund).containsExactly(
            new PaymentAdminInfo.ExternalRefund(Money.zero(CurrencyCode.USD), Money.of(1250, CurrencyCode.USD)),
            null,
            new PaymentAdminInfo.ExternalRefund(Money.of(500, CurrencyCode.KRW), Money.of(300, CurrencyCode.KRW)));
    }

    @Test
    @DisplayName("빈 페이지면 집계 질의를 하지 않는다")
    void emptyPageSkipsAggregate() {
        given(port.search(any())).willReturn(page());
        assertThat(processor.search(COMMAND).getContent()).isEmpty();
        verify(port, never()).findExternalRefundTotals(any());
    }

    @Test
    @DisplayName("원장 통화가 결제 통화와 다르면 데이터 오류라 조용히 넘기지 않는다")
    void currencyMismatchFailsLoudly() {
        given(port.search(any())).willReturn(page(row(1, CurrencyCode.KRW)));
        given(port.findExternalRefundTotals(any())).willReturn(List.of(total(1, PaymentCancel.Status.OFF_PG_REFUNDED, 100, CurrencyCode.USD)));
        assertThatThrownBy(() -> processor.search(COMMAND)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Page<PaymentAdminQueryResult> page(PaymentAdminQueryResult... rows) {
        return new PageImpl<>(List.of(rows), PageRequest.of(0, 20), rows.length);
    }

    private static PaymentAdminQueryResult row(long paymentId, CurrencyCode currency) {
        return new PaymentAdminQueryResult(paymentId, 100 + paymentId, PaymentStatus.DONE, 129000, currency, null, null, true, null, TIME, TIME, TIME, TIME);
    }

    private static PaymentAdminExternalRefundQueryResult total(long paymentId, PaymentCancel.Status status, long amount, CurrencyCode currency) {
        return new PaymentAdminExternalRefundQueryResult(paymentId, status, amount, currency);
    }
}
