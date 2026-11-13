package com.commercefoundry.domainlayer.payment.application.service.processor;

import com.commercefoundry.domainlayer.payment.application.command.PaymentAdminSearchCommand;
import com.commercefoundry.domainlayer.payment.application.info.PaymentAdminInfo;
import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAdminQueryPort;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminExternalRefundQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAdminQueryProcessor {
    private final PaymentAdminQueryPort queryPort;

    public Page<PaymentAdminInfo> search(PaymentAdminSearchCommand command) {
        Page<PaymentAdminQueryResult> page = queryPort.search(
            new PaymentAdminSearchCriteria(command.status(), command.orderId(), command.orderSynced(), command.page(), command.size()));
        Map<Long, PaymentAdminInfo.ExternalRefund> externalRefunds = externalRefundsOf(page.getContent());
        return page.map(row -> new PaymentAdminInfo(row.paymentId(), row.orderId(), row.status(), row.amount(), row.method(), row.unknownReason(),
            row.orderSynced(), row.orderSyncFailureCode(), row.createdAt(), row.updatedAt(), row.approvalStartedAt(), row.approvedAt(),
            externalRefunds.get(row.paymentId())));
    }

    /**
     * 페이지의 결제 아이디로 외부 확인 환불 합계를 한 번에 읽어 결제별로 묶는다 (#693). 빈 페이지면 질의하지 않는다.
     * 없는 채널은 결제 통화의 0 이다. 원장 통화가 결제 통화와 다르면 데이터 오류다. 운영 화면 전체를 막지 않도록 그 결제만
     * {@code externalRefund} 를 비우고(null) 결제 아이디를 ERROR 로그로 남긴다 — 금액은 남기지 않는다.
     */
    private Map<Long, PaymentAdminInfo.ExternalRefund> externalRefundsOf(List<PaymentAdminQueryResult> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, CurrencyCode> currencies = rows.stream()
            .collect(Collectors.toMap(PaymentAdminQueryResult::paymentId, PaymentAdminQueryResult::totalCurrency));
        Map<Long, PaymentAdminInfo.ExternalRefund> totals = new HashMap<>();
        Set<Long> mismatched = new HashSet<>();
        for (PaymentAdminExternalRefundQueryResult total : queryPort.findExternalRefundTotals(currencies.keySet())) {
            CurrencyCode currency = currencies.get(total.paymentId());
            if (mismatched.contains(total.paymentId()) || total.money().currency() != currency) {
                if (mismatched.add(total.paymentId())) {
                    log.error("code=PAYMENT_ADMIN_EXTERNAL_REFUND_CURRENCY_MISMATCH paymentId={}", total.paymentId());
                }
                totals.remove(total.paymentId());
                continue;
            }
            PaymentAdminInfo.ExternalRefund current = totals.getOrDefault(total.paymentId(),
                new PaymentAdminInfo.ExternalRefund(Money.zero(currency), Money.zero(currency)));
            totals.put(total.paymentId(), total.status().reducesPgBalance()
                ? new PaymentAdminInfo.ExternalRefund(current.pgAmount().plus(total.money()), current.offPgAmount())
                : new PaymentAdminInfo.ExternalRefund(current.pgAmount(), current.offPgAmount().plus(total.money())));
        }
        return totals;
    }
}
