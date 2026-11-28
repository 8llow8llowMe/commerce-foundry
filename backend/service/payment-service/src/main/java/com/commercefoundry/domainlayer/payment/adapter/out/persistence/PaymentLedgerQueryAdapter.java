package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentCancelRepository;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentRepository;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapper;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentLedgerQueryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산 대사용 원장 벌크 조회 (#219). 목록은 {@value #IN_CLAUSE_CHUNK} 개씩 나눠 IN 절 한 문장으로 읽는다 — 하루 거래가 늘어도 바인드 파라미터 상한(PostgreSQL 32767)을 넘지 않게.
 * 나눈 조각마다 한 번이라 건별 조회(N+1)가 아니다.
 */
@Component
@RequiredArgsConstructor
public class PaymentLedgerQueryAdapter implements PaymentLedgerQueryPort {

    static final int IN_CLAUSE_CHUNK = 500;

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;
    private final PaymentMapper paymentMapper;

    @Override
    public List<Payment> findByPaymentKeys(Collection<String> paymentKeys) {
        return chunked(List.copyOf(paymentKeys), chunk -> paymentRepository.findAllByPaymentKeyIn(chunk).stream().map(paymentMapper::toDomain).toList());
    }

    @Override
    public List<Payment> findApprovedBetween(Instant from, Instant to, CurrencyCode currency) {
        return paymentRepository.findApprovedBetween(from, to, currency).stream().map(paymentMapper::toDomain).toList();
    }

    @Override
    public List<PaymentCancel> findCancelsByPaymentIds(Collection<Long> paymentIds) {
        return chunked(List.copyOf(paymentIds),
            chunk -> paymentCancelRepository.findAllByPaymentIdIn(chunk).stream().map(PaymentCancelEntity::toDomain).toList());
    }

    private static <K, V> List<V> chunked(List<K> keys, Function<List<K>, List<V>> query) {
        List<V> result = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += IN_CLAUSE_CHUNK) {
            result.addAll(query.apply(keys.subList(from, Math.min(keys.size(), from + IN_CLAUSE_CHUNK))));
        }
        return result;
    }
}
