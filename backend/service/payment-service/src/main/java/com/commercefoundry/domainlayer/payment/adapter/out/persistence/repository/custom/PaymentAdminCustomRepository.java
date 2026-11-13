package com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminExternalRefundQueryResult;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;

public interface PaymentAdminCustomRepository {
    Page<PaymentAdminQueryResult> search(PaymentAdminSearchCriteria criteria);

    /** 비어 있지 않은 결제 아이디 묶음의 외부 확인 환불 합계 — 결제 · 상태 · 통화별 GROUP BY 한 문장이다. */
    List<PaymentAdminExternalRefundQueryResult> findExternalRefundTotals(Collection<Long> paymentIds);
}
