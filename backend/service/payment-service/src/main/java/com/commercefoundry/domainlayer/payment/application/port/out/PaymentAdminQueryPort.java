package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.application.model.PaymentAdminSearchCriteria;
import com.commercefoundry.domainlayer.payment.application.port.out.query.PaymentAdminQueryResult;
import org.springframework.data.domain.Page;

/** 어드민의 읽기 전용 결제 목록. 저장 · PG 조회 책임은 없다. */
public interface PaymentAdminQueryPort {
    Page<PaymentAdminQueryResult> search(PaymentAdminSearchCriteria criteria);
}
