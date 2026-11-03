package com.commercefoundry.domainlayer.payment.adapter.in.event;

import com.commercefoundry.domainlayer.payment.support.OrderPaymentReplyConsumerContract;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 주문 결제 회신 컨슈머 계약을 PostgreSQL 17 로 돈다 — 운영 DDL 스냅숏(processed_event 포함)을 넣고 {@code ddl-auto=validate} 로 엔티티(uuid · timestamptz)와 맞는지,
 * inbox 유니크 · 버전 경합이 실제 엔진에서 같은 결과인지 본다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class OrderPaymentReplyConsumerPostgresTest extends OrderPaymentReplyConsumerContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");
}
