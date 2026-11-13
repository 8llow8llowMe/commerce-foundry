package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminExternalRefundQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;

/** 어드민의 읽기 전용 결제 목록. 저장 · PG 조회 책임은 없다. */
public interface PaymentAdminQueryPort {
    Page<PaymentAdminQueryResult> search(PaymentAdminSearchCriteria criteria);

    /**
     * 주어진 결제들의 외부 확인 환불 원장(EXTERNAL_PG_REFUNDED · OFF_PG_REFUNDED)을 결제 · 상태 · 통화별로 합산해 한 번에 읽는다 (#693).
     * 원장이 없는 결제는 결과에 없다. 빈 입력이면 질의 없이 빈 목록이다.
     */
    List<PaymentAdminExternalRefundQueryResult> findExternalRefundTotals(Collection<Long> paymentIds);
}
