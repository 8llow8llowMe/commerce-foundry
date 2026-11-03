package com.commercefoundry.domainlayer.payment.adapter.in.event;

import com.commercefoundry.domainlayer.payment.support.OrderPaymentReplyConsumerContract;
import org.springframework.test.context.TestPropertySource;

/** 주문 결제 회신 컨슈머 계약을 H2(PostgreSQL 모드, 엔티티로 만든 스키마)에서 돈다 — Docker 없이 로컬 · CI 가 같은 결과를 낸다. */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-order-reply;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS payment",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrderPaymentReplyConsumerH2Test extends OrderPaymentReplyConsumerContract {
}
